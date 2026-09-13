package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import com.jcraft.jsch.ChannelShell;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH 交互式终端的公共状态与并发控制。
 *
 * <p>核心数据流：</p>
 * <pre>
 *                        ┌─> AgentCommandCapture（Agent 独立命令结果）
 * SSH InputStream -> Reader
 *                        └─> outputBuffer -> HTTP Long Poll -> xterm.js
 * </pre>
 *
 * <p>Reader 始终只有一个。它读取一次 SSH 输出后，把同一份数据分发给两个用途不同的
 * 消费者。这样 Agent 能等待一条命令的完整结果，前端也能持续刷新终端，两者不会通过
 * {@code readAsync()} 竞争同一个缓冲区。</p>
 *
 * @author jasonlat
 * 2026-09-13  01:56
 */
@Slf4j
public class TerminalSessionPortSupport {


    /**
     * SSH 输出单次读取缓冲区。
     * 这里使用 char[]，而不是 byte[]。
     * 因为 InputStreamReader 会负责 UTF-8 解码，
     * 可以正确处理：
     * 第一次 read：
     * E4 BD
     * 第二次 read：
     * A0
     * 这种一个 UTF-8 中文字符被拆成两次读取的情况。
     */
    private static final int READ_BUFFER_SIZE = 8192;

    /**
     * 每个终端最大未消费输出。
     * 这里限制为 2M 个字符。
     * 防止例如：
     * tail -f
     * journalctl -f
     * 大量日志打印
     * 而前端又停止读取时，outputBuffer 无限增长，
     * 最终导致 JVM OOM。
     */
    private static final int MAX_OUTPUT_BUFFER_SIZE = 2 * 1024 * 1024;

    /**
     * Agent 单条命令最多保留的输出字符数。
     * Agent 必须等结束标记出现后才能返回完整结果，因此执行期间的内容不能像前端缓冲区
     * 一样被 Long Poll 分批消费。设置上限可防止 tail -f 等持续输出命令耗尽 JVM 内存。
     */
    protected static final int MAX_AGENT_COMMAND_OUTPUT_SIZE = 2 * 1024 * 1024;



    /**
     * sessionId -> TerminalSessionContext
     * 所有和 Terminal 有关的数据统一放进 TerminalSessionContext。
     * 不再像旧版本一样分别维护：
     * channels
     * inputStreams
     * outputStreams
     * outputBuffers
     * readerAlive
     * 这样可以明显降低 Session 状态错乱的可能性。
     */
    protected final Map<String, TerminalSessionContext> terminalSessions = new ConcurrentHashMap<>();

    /**
     * connectionId -> 当前 Terminal sessionId
     * 一个 SSH connection 只允许创建一个 Terminal。
     * 如果再次打开，则关闭之前的 Terminal。
     */
    protected final Map<String, String> activeConnectionSession = new ConcurrentHashMap<>();

    // ==========================================================
    // Long Polling
    // ==========================================================

    /**
     * Long Polling 最大等待时间。
     * 25 秒内如果 SSH 一直没有产生新数据，
     * 本次 HTTP 请求返回 TIMEOUT。
     * 注意：
     * TIMEOUT 不代表 SSH 命令执行完成，
     * 只是让当前 HTTP Long Poll 正常结束，
     * 前端随后重新建立下一次 Long Poll。
     */
    protected static final long LONG_POLL_TIMEOUT_SECONDS = 25L;

    // ============================================================
    // Terminal Session 上下文
    // ============================================================
    /**
     * 一个 Terminal 对应一个 Context。
     * 与这个 Terminal 有关的所有状态全部放在一起。
     * 这样比维护多个：
     * Map<sessionId, Channel>
     * Map<sessionId, InputStream>
     * Map<sessionId, OutputStream>
     * Map<sessionId, Buffer>
     * Map<sessionId, readerAlive>
     * 更安全，也更容易管理生命周期。
     */
    protected static final class TerminalSessionContext {
        /**
         * Terminal Session ID。
         */
        final String sessionId;
        /**
         * 所属 SSH connection ID。
         */
        final String connectionId;
        /**
         * SSH Shell Channel。
         */
        final ChannelShell channel;
        /**
         * SSH -> Java。
         */
        final InputStream inputStream;
        /**
         * Java -> SSH。
         */
        final OutputStream outputStream;
        /**
         * 当前还没有被前端读取的 SSH 输出。
         */
        final StringBuilder outputBuffer = new StringBuilder(16 * 1024);

        /**
         * 是否存在 reader 正在运行。
         * compareAndSet 保证一个 Session
         * 永远最多只有一个 reader。
         */
        final AtomicBoolean readerRunning = new AtomicBoolean(false);

        /**
         * Terminal 是否已经主动关闭。
         */
        final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 是否已经读取到 EOF。
         */
        final AtomicBoolean eofReached = new AtomicBoolean(false);

        /**
         * reader 是否异常退出。
         */
        final AtomicBoolean readerFailed = new AtomicBoolean(false);

        /**
         * Buffer 是否发生过溢出。
         * read() 消费后重新设置为 false。
         */
        final AtomicBoolean bufferOverflowed = new AtomicBoolean(false);

        /**
         * SSH 状态提示是否已经返回给前端。
         * 防止每轮 polling 都返回：
         * [SSH连接已断开]
         */
        final AtomicBoolean stateMessageReturned = new AtomicBoolean(false);

        /**
         * SSH 写锁。
         * 防止多个 HTTP 请求同时向同一个 OutputStream 写数据。
         */
        final Object writeLock = new Object();

        /**
         * 保证同一个 Terminal 同时只执行一条 Agent 命令。
         * 多条命令并行写入同一个交互式 Shell 时，输出会交叉，无法准确判断每条命令
         * 的结束位置，因此 Agent 命令必须串行执行。
         */
        final Object agentCommandLock = new Object();

        /**
         * 保护 activeAgentCommand 及捕获器内部状态。SSH Reader 线程负责追加数据，
         * Agent 调用线程负责创建和清理捕获器，这两个线程不能同时修改捕获器。
         */
        final Object agentCaptureLock = new Object();

        /**
         * 当前 Agent 命令的独立输出捕获器；没有 Agent 命令执行时为 null。
         * 它与前端 Long Poll 使用的 outputBuffer 是两套独立存储。
         */
        AgentCommandCapture activeAgentCommand;

        /**
         * 当前 Terminal Reader Thread。
         */
        volatile Thread readerThread;

        /**
         * Terminal 数据事件锁。 outputBuffer 的：
         * 1. 检查
         * 2. 读取
         * 3. 清空
         * 4. 写入
         * 5. pendingRead 注册
         * 全部使用同一个锁。
         * 这样能够避免 Long Polling 最典型的“丢通知”问题：
         * 请求线程检查 buffer 没数据
         *              ↓
         * SSH reader 数据正好到达
         *              ↓
         * 请求线程再注册 pendingRead
         * 如果没有统一锁，就可能导致：
         * 数据已经进入 buffer，
         * 但是这个 Long Poll 却一直等到超时。
         */
        final Object eventLock = new Object();


        /**
         * 当前正在等待 SSH 数据的 Long Poll 请求。
         * 一个 Terminal 同一时刻只允许存在一个 Long Poll。
         * 正常前端逻辑应该是：
         * 第一次请求结束
         *      ↓
         * 再发下一次请求
         * 而不是并行发多个 read 请求。
         */
        CompletableFuture<TerminalReadResult> pendingRead;


        TerminalSessionContext(String sessionId, String connectionId, ChannelShell channel,
                                       InputStream inputStream, OutputStream outputStream) {
            this.sessionId = sessionId;
            this.connectionId = connectionId;
            this.channel = channel;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }
    }

    /**
     * 一条 Agent 命令的流式输出捕获器。
     *
     * <p>基础设施层实际写入 Shell 的结构为：</p>
     * <pre>
     * printf START_MARKER
     * eval '用户命令'
     * printf END_MARKER:退出码
     * </pre>
     *
     * <p>START/END 使用控制字符和随机 UUID 组成，普通命令输出几乎不会与它碰撞。
     * SSH 输出是流式到达的，一个标记可能被拆到多次 read 中，因此 pending 必须保留
     * 尚不能确定是普通输出还是标记前缀的尾部字符。</p>
     *
     * <p>accept() 返回允许展示给前端的内容；result 则在收到完整结束标记以后，
     * 向等待中的 Agent 返回完整命令结果。</p>
     */
    protected static final class AgentCommandCapture {
        /** 真实命令输出开始标记；它之前通常是 Shell 对包装命令的回显。 */
        final String startMarker;

        /** 真实命令输出结束标记前缀；其后紧跟命令退出码和 US 结束字符。 */
        final String endMarkerPrefix;

        /**
         * 展示给 xterm.js 的原始 Agent 命令。
         * Shell 实际收到的是包含 printf/eval 的包装脚本，不能把包装脚本直接显示给用户。
         */
        final String displayCommand;

        /** 未完成边界判断的数据，也保存跨越两个 SSH read 的不完整标记。 */
        final StringBuilder pending = new StringBuilder();

        /** 只属于 Agent 的命令结果，不会被前端 Long Poll 消费或清空。 */
        final StringBuilder output = new StringBuilder();

        /** Agent 调用线程等待的 Future，由 SSH Reader 在线程安全区域内完成。 */
        final CompletableFuture<String> result = new CompletableFuture<>();

        /** 是否已经识别到 START_MARKER。 */
        boolean started;

        /** 是否已经成功结束，或因输出溢出、断开等原因失败。 */
        boolean completed;

        AgentCommandCapture(String startMarker, String endMarkerPrefix, String displayCommand) {
            this.startMarker = startMarker;
            this.endMarkerPrefix = endMarkerPrefix;
            this.displayCommand = displayCommand;
        }

        String accept(String incoming) {
            // 命令结束后的数据属于后续 Shell prompt 或用户输入，直接交给前端。
            if (completed) {
                return incoming;
            }

            pending.append(incoming);
            String displayPrefix = "";
            if (!started) {
                int start = pending.indexOf(startMarker);
                if (start < 0) {
                    /*
                     * 还没收到完整开始标记。包装命令的回显不展示给前端，但必须保留
                     * pending 尾部可能属于 START_MARKER 开头的字符，以处理跨块标记。
                     */
                    retainPossibleMarkerPrefix(pending, startMarker);
                    return "";
                }
                // 删除包装命令回显和开始标记，后面的字符才是真实命令输出。
                pending.delete(0, start + startMarker.length());
                started = true;

                /*
                 * 包装脚本已经被过滤，此处补回用户真正关心的命令文本。终端之前已经显示
                 * Shell prompt，所以直接写“命令 + 换行”即可形成正常的交互式终端记录。
                 */
                displayPrefix = displayCommand + "\r\n";
            }

            int end = pending.indexOf(endMarkerPrefix);
            if (end < 0) {
                /*
                 * 结束标记尚未完整出现。先消费确定不属于标记的内容，只留下可能构成
                 * END_MARKER 的末尾字符等待下一块 SSH 数据。
                 */
                int safeLength = pending.length() - markerPrefixOverlap(pending, endMarkerPrefix);
                return displayPrefix + consumeOutput(safeLength);
            }

            int exitCodeStart = end + endMarkerPrefix.length();
            int markerEnd = pending.indexOf("\u001f", exitCodeStart);
            if (markerEnd < 0) {
                // 已收到结束标记前缀，但退出码还没收完整，只消费标记前的命令输出。
                return displayPrefix + consumeOutput(end);
            }

            /*
             * consumeOutput(end) 会删掉 pending 中结束标记以前的内容，所以后续索引
             * 需要按“结束标记当前位于下标 0”重新计算。
             */
            String displayOutput = consumeOutput(end);
            int adjustedExitCodeStart = endMarkerPrefix.length();
            int adjustedMarkerEnd = pending.indexOf("\u001f", adjustedExitCodeStart);
            String exitCode = pending.substring(adjustedExitCodeStart, adjustedMarkerEnd).trim();
            String afterMarker = pending.substring(adjustedMarkerEnd + 1);
            pending.setLength(0);
            completed = true;

            String commandOutput = trimBoundaryLineBreaks(output.toString());
            if (!"0".equals(exitCode)) {
                // 非零退出码显式附加到结果中，Agent 才能稳定判断命令是否执行成功。
                if (!commandOutput.isEmpty()) commandOutput += System.lineSeparator();
                commandOutput += "[命令退出码: " + exitCode + "]";
            }
            result.complete(commandOutput);
            return displayPrefix + displayOutput + afterMarker;
        }

        private String consumeOutput(int length) {
            if (length <= 0) return "";
            String value = pending.substring(0, length);
            pending.delete(0, length);
            if (output.length() + value.length() > MAX_AGENT_COMMAND_OUTPUT_SIZE) {
                /*
                 * 这段数据仍返回给前端显示，但 Agent 结果已无法保证完整，因此用异常
                 * 完成 Future，不能把截断内容作为一次成功结果返回。
                 */
                completed = true;
                result.completeExceptionally(new IllegalStateException("SSH 命令输出超过 Agent 可接收上限"));
                return value;
            }
            output.append(value);
            return value;
        }

        private static void retainPossibleMarkerPrefix(StringBuilder value, String marker) {
            int overlap = markerPrefixOverlap(value, marker);
            if (value.length() > overlap) value.delete(0, value.length() - overlap);
        }

        private static int markerPrefixOverlap(StringBuilder value, String marker) {
            /*
             * 寻找 value 的最长后缀，同时也是 marker 的前缀。例如 value 以 SSH_AGE
             * 结尾，下一块以 NT_END 开始时，两块合并后才是完整边界标记。
             */
            int max = Math.min(value.length(), marker.length() - 1);
            for (int length = max; length > 0; length--) {
                int offset = value.length() - length;
                boolean matches = true;
                for (int i = 0; i < length; i++) {
                    if (value.charAt(offset + i) != marker.charAt(i)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return length;
            }
            return 0;
        }

        private static String trimBoundaryLineBreaks(String value) {
            int start = 0;
            int end = value.length();
            while (start < end && (value.charAt(start) == '\r' || value.charAt(start) == '\n')) start++;
            while (end > start && (value.charAt(end - 1) == '\r' || value.charAt(end - 1) == '\n')) end--;
            return value.substring(start, end);
        }
    }

    /**
     * SSH Reader 收到新的 Terminal 输出。
     * 两种情况：
     * 一、有 Long Poll 正在等待
     * SSH Reader
     *      ↓
     * appendOutput()
     *      ↓
     * complete(pendingRead)
     *      ↓
     * HTTP 立即返回
     * 二、当前没有 Long Poll
     * SSH Reader
     *      ↓
     * outputBuffer
     *      ↓
     * 等下一个 read() 请求过来直接消费
     */
    protected void appendOutput(TerminalSessionContext context, char[] chars, int len) {
        if (len <= 0 || context.closed.get()) {
            return;
        }

        String incoming = new String(chars, 0, len);
        String terminalOutput;
        synchronized (context.agentCaptureLock) {
            AgentCommandCapture capture = context.activeAgentCommand;
            /*
             * 无 Agent 命令时原样进入前端缓冲区；
             * 有 Agent 命令时，capture 一边把真实输出复制到 Agent 独立缓冲区，一边过滤仅用于内部定位的包装命令与标记。
             */
            terminalOutput = capture == null ? incoming : capture.accept(incoming);
        }

        /*
         * Agent 的包装命令回显或边界标记可能被捕获器完整过滤。
         * 此时不要用空 DATA 提前结束前端 Long Poll。
         */
        if (terminalOutput.isEmpty()) {
            return;
        }

        CompletableFuture<TerminalReadResult> pendingFuture = null;
        TerminalReadResult readResult = null;

        synchronized (context.eventLock) {
            /*
             * cleanup() 可能刚好发生，
             * 所以拿锁之后再检查一次。
             */
            if (context.closed.get()) {
                return;
            }
            /*
             * ==========================================
             * 1. 防止 Terminal Buffer 无限增长
             * ==========================================
             */
            int currentSize = context.outputBuffer.length();
            /*
             * 正常情况下 READ_BUFFER_SIZE 远小于 MAX_OUTPUT_BUFFER_SIZE。这里仍然防御一次。
             */
            if (terminalOutput.length() >= MAX_OUTPUT_BUFFER_SIZE) {
                context.outputBuffer.setLength(0);
                /*
                 * 只保留这一批数据的最后 MAX_SIZE 部分。
                 */
                int start = terminalOutput.length() - MAX_OUTPUT_BUFFER_SIZE;
                context.outputBuffer.append(terminalOutput, start, terminalOutput.length());
                context.bufferOverflowed.set(true);
            } else {
                /*
                 * Buffer 放不下新数据。
                 */
                if (currentSize + terminalOutput.length() > MAX_OUTPUT_BUFFER_SIZE) {
                    /*
                     * 丢弃旧数据，优先保留最新 Terminal 输出。
                     * 对 Terminal 场景来说：保证 JVM 不 OOM 比无限保存所有历史输出更加重要。
                     */
                    context.outputBuffer.setLength(0);

                    context.bufferOverflowed.set(true);
                    log.warn("Terminal输出缓冲区溢出，丢弃部分旧数据 sessionId={} oldSize={} incomingSize={} maxSize={}",
                            context.sessionId, currentSize, terminalOutput.length(), MAX_OUTPUT_BUFFER_SIZE
                    );
                }
                /*
                 * 保存本次 SSH 输出。
                 */
                context.outputBuffer.append(terminalOutput);
            }


            /*
             * ==========================================
             * 2. 是否有 Long Poll 正在等待
             * ==========================================
             */
            if (context.pendingRead != null && !context.pendingRead.isDone()) {
                pendingFuture = context.pendingRead;
                /*
                 * 先解除引用。后续新的 SSH 输出不能再 complete同一个 Long Poll。
                 */
                context.pendingRead = null;

                /*
                 * 把目前所有 SSH 数据一次性取走。
                 */
                String data = consumeOutputBuffer(context);
                /*
                 * Buffer Overflow 标识只返回一次。
                 */
                boolean bufferOverflow = context.bufferOverflowed.getAndSet(false);
                readResult = TerminalReadResult.data(data, isChannelConnected(context.channel), bufferOverflow
                );
            }
        }

        /*
         * ==========================================
         * 3. 锁外完成 Long Poll
         * ==========================================
         * 不要放在 synchronized(eventLock) 内。
         */
        if (pendingFuture != null) {
            pendingFuture.complete(readResult);
        }
    }


    /**
     * 消费当前 Terminal 输出缓冲区。
     * 注意：
     * 调用这个方法之前，
     * 必须已经持有：
     * synchronized(context.eventLock)
     */
    protected String consumeOutputBuffer(TerminalSessionContext context) {
        if (context.outputBuffer.isEmpty()) {
            return "";
        }
        String data = context.outputBuffer.toString();
        /*
         * 数据已经交给当前 HTTP 请求，
         * 所以清空缓冲区。
         */
        context.outputBuffer.setLength(0);
        return data;
    }

    // ============================================================
    // Session 管理
    // ============================================================

    /**
     * 获取 Terminal Session。
     * 不存在时统一抛业务异常。
     */
    protected TerminalSessionContext getTerminalSession(String sessionId) {
        TerminalSessionContext context = terminalSessions.get(sessionId);
        if (context == null || context.closed.get()) {
            log.warn("Terminal 会话不存在或已经关闭 sessionId={}", sessionId);
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        return context;
    }

    /**
     * 判断 Shell Channel 是否正常连接。
     */
    protected boolean isChannelConnected( ChannelShell channel) {
        return channel != null && channel.isConnected() && !channel.isClosed();
    }


    /**
     * 清理 Terminal Session。
     * 本方法设计为：可以被重复调用。
     * 例如：
     * closeSession()
     * +
     * openTerminal() 清理旧 Terminal
     * 即使同时触发，也不会重复释放资源。
     */
    protected void cleanup(String sessionId) {
        /*
         * remove 是原子操作。
         * 第一个调用 cleanup 的线程可以拿到 context。
         * 后续线程再 cleanup：
         * context == null
         * 直接结束。
         */
        TerminalSessionContext context = terminalSessions.remove(sessionId);
        if (context == null) {
            return;
        }
        /*
         * 标记 Terminal 已经关闭。
         * reader 看到 closed=true 后会主动结束。
         */
        context.closed.set(true);

        /*
         * Terminal 被关闭以后，如果前端正有 Long Poll 请求挂起，必须立即结束。
         * 不能让请求继续等待到 25 秒超时。这里是主动关闭，所以不一定真的读取到了 EOF，eof=false。
         */
        completePendingRead(context, TerminalReadResult.disconnected(false));
        failActiveAgentCommand(context, "SSH 终端会话已关闭");

        /*
         * 只删除：
         * connectionId -> 当前这个 sessionId
         * 使用 ConcurrentHashMap.remove(key, value)
         * 是为了避免这种场景：
         * old session cleanup 很慢
         * 新 session 已经建立
         * 如果此时直接 remove(connectionId)
         * 有可能误删新 session。
         */
        activeConnectionSession.remove(context.connectionId, context.sessionId);

        /*
         * 尝试 interrupt reader。
         * InputStream.read() 不一定响应 interrupt，
         * 所以下面还会关闭 InputStream 和 Channel。
         */
        Thread readerThread = context.readerThread;


        if (readerThread != null && readerThread != Thread.currentThread()) {
            readerThread.interrupt();
        }

        /*
         * ================================
         * 关闭输出流
         * ================================
         */
        closeQuietly(context.outputStream);


        /*
         * ================================
         * 关闭输入流
         * ================================
         * 这通常会让阻塞中的：
         * reader.read()
         * 立即退出。
         */
        closeQuietly(context.inputStream);

        /*
         * ================================
         * 关闭 Channel
         * ================================
         */
        disconnectQuietly(context.channel);

        /*
         * ================================
         * 清理 Buffer
         * ================================
         */
        synchronized (context.eventLock) {
            context.outputBuffer.setLength(0);
            context.bufferOverflowed.set(false);
        }

        log.info("Terminal 会话清理完成 sessionId={} connectionId={}", context.sessionId, context.connectionId);
    }


    /**
     * 安全关闭 InputStream。
     */
    protected void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            log.debug("关闭 Terminal InputStream 异常 reason={}",e.getMessage());
        }
    }

    /**
     * 安全关闭 OutputStream。
     */
    protected void closeQuietly(OutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException e) {
            log.debug("关闭 Terminal OutputStream 异常 reason={}", e.getMessage());
        }
    }


    /**
     * 安全关闭 SSH Shell Channel。
     */
    protected void disconnectQuietly(ChannelShell channel) {
        if (channel == null) {
            return;
        }
        try {
            if (channel.isConnected()) {
                channel.disconnect();
            }
        } catch (Exception e) {
            log.debug("关闭 Terminal Channel 异常 reason={}", e.getMessage());
        }
    }


    /**
     * 主动结束当前正在等待的 Long Poll。
     * 主要用于：
     * SSH EOF
     * SSH Reader 异常
     * Terminal 被主动关闭
     */
    protected void completePendingRead(TerminalSessionContext context, TerminalReadResult result) {
        CompletableFuture<TerminalReadResult> future;
        synchronized (context.eventLock) {
            future = context.pendingRead;
            /*
             * 先解除 pendingRead。
             */
            context.pendingRead = null;
        }
        /*
         * complete 一定放在锁外。
         */
        if (future != null && !future.isDone()) {
            future.complete(result);
        }
    }

    /**
     * 当前 SSH 会话失效时立即唤醒正在等待结果的 Agent。
     *
     * <p>如果 Reader 已退出却不完成 result，executeCommand() 只能一直等到命令超时。
     * 这里先在锁内摘除捕获器，再在锁外 completeExceptionally，避免 Future 回调在锁内
     * 重新进入终端代码而形成复杂锁关系。</p>
     */
    protected void failActiveAgentCommand(TerminalSessionContext context, String message) {
        CompletableFuture<String> result = null;
        synchronized (context.agentCaptureLock) {
            if (context.activeAgentCommand != null) {
                result = context.activeAgentCommand.result;
                context.activeAgentCommand.completed = true;
                context.activeAgentCommand = null;
            }
        }
        if (result != null && !result.isDone()) {
            result.completeExceptionally(new IllegalStateException(message));
        }
    }

    // ============================================================
    // SSH Reader
    // ============================================================
    /**
     * 启动 SSH 输出读取线程。
     * 一个 TerminalSessionContext
     * 整个生命周期只允许存在一个 reader。
     */
    protected void startOutputReader(TerminalSessionContext context) {
        /*
         * compareAndSet 是这里最关键的一步。
         * false -> true 成功： 当前没有 reader，可以启动。
         * false -> true 失败：说明已经有 reader 运行，禁止再创建第二个。
         */
        if (!context.readerRunning.compareAndSet(false, true)) {
            log.debug("Terminal reader 已经运行，忽略重复启动 sessionId={}", context.sessionId);
            return;
        }
        Thread readerThread = new Thread(
                () -> runOutputReader(context), "terminal-reader-" + context.sessionId);

        /*
         * 保存 reader Thread。
         * cleanup 时可以 interrupt。
         */
        context.readerThread = readerThread;

        /*
         * daemon thread。
         * 防止 JVM 关闭时 Terminal reader
         * 阻止应用退出。
         */
        readerThread.setDaemon(true);
        readerThread.start();
        log.debug("Terminal reader 启动成功 sessionId={}", context.sessionId);
    }


    /**
     * 真正执行 SSH 输出读取。
     */
    protected void runOutputReader(TerminalSessionContext context) {
        /*
         * ========================================================
         * 重要：
         * 不再使用：
         * byte[] buf
         * +
         * new String(buf, 0, len, UTF_8)
         * 因为 SSH InputStream 是字节流。
         * UTF-8 中文字符例如：
         * "你"
         * UTF-8：
         * E4 BD A0
         * 完全有可能：
         * read #1：
         * E4 BD
         * read #2：
         * A0
         * 如果每次直接 new String，
         * 就可能产生 �。
         * InputStreamReader 内部会保存未完整的 UTF-8 字节，
         * 等下一次读取后再完成字符解码。
         * ========================================================
         */
        InputStreamReader reader = new InputStreamReader(context.inputStream, StandardCharsets.UTF_8);

        char[] readBuffer = new char[READ_BUFFER_SIZE];
        try {
            while (!context.closed.get()) {
                int len;
                try {
                    /*
                     * 这里是阻塞读取。
                     * 没有 SSH 输出时线程会睡眠在 read()，
                     * 不会占用 CPU 空转。
                     */
                    len = reader.read(readBuffer);

                } catch (SocketTimeoutException e) {
                    /*
                     * SocketTimeout 不等于 SSH 断开。
                     * 例如底层 socket 设置了 read timeout，
                     * 一段时间没有 SSH 输出就可能抛这个异常。
                     * 直接继续下一次 read 即可。
                     * 千万不要重新启动新的 reader Thread。
                     */
                    log.debug("Terminal reader SocketTimeout，继续等待 sessionId={}", context.sessionId
                    );
                    continue;
                }

                /*
                 * Reader 返回 -1 表示 EOF。
                 * Shell Channel 已经结束。
                 */
                if (len == -1) {
                    /*
                     * 标记已经读取到真正 EOF。
                     */
                    context.eofReached.set(true);
                    log.info("Terminal Shell EOF sessionId={}", context.sessionId);
                    /*
                     * 如果当前正好有 HTTP Long Poll 挂着，
                     * 不要让它继续等 25 秒。
                     * 立即告诉前端 SSH 已经断开。
                     */
                    completePendingRead(context, TerminalReadResult.disconnected(true));
                    failActiveAgentCommand(context, "SSH 在命令执行期间断开（EOF）");
                    break;
                }
                /*
                 * 防御性处理。
                 */
                if (len == 0) {
                    continue;
                }
                /*
                 * 把读取到的数据追加进入输出缓冲区。
                 */
                appendOutput(context, readBuffer, len);
            }
        } catch (IOException e) {
            /*
             * 如果是 cleanup() 主动关闭导致的 IOException，
             * 不认为是异常。
             */
            if (context.closed.get()) {
                log.debug("Terminal reader 因会话关闭而退出 sessionId={}", context.sessionId);
                return;
            }
            /*
             * 如果此时 channel 仍然显示 connected，
             * 说明 reader 出现了真正的异常。
             * 这里不重新创建 reader。
             */
            if (isChannelConnected(context.channel)) {
                /*
                 * Channel 看上去仍然连接，
                 * 但是 Reader 已经发生异常。
                 * 不再尝试创建第二个 Reader。
                 */
                context.readerFailed.set(true);
                log.error("Terminal reader I/O异常，但Channel仍连接 sessionId={} reason={}", context.sessionId, e.getMessage(), e);
                /*
                 * Long Poll 立即返回 READER_ERROR。
                 */
                completePendingRead(context, TerminalReadResult.readerError(true));
                failActiveAgentCommand(context, "SSH Reader 在命令执行期间异常");
            } else {
                /*
                 * SSH Channel 已经真正断开。
                 */
                context.eofReached.set(true);
                log.info("Terminal reader 因SSH断开退出 sessionId={} reason={}", context.sessionId, e.getMessage());
                /*
                 * Long Poll 立即返回 DISCONNECTED。
                 * 这里不一定真的读取到了 -1，因此 eof=false 更严谨。
                 */
                completePendingRead(context, TerminalReadResult.disconnected(false));
                failActiveAgentCommand(context, "SSH 在命令执行期间断开");
            }
        } catch (Exception e) {
            if (!context.closed.get()) {
                context.readerFailed.set(true);
                log.error("Terminal reader 未知异常 sessionId={}", context.sessionId, e);
                completePendingRead(
                        context,
                        TerminalReadResult.readerError(
                                isChannelConnected(context.channel)
                        )
                );
                failActiveAgentCommand(context, "SSH Reader 在命令执行期间异常");
            }
        } finally {
            /*
             * reader 生命周期结束。
             */
            context.readerRunning.set(false);
            log.info("Terminal reader 已退出 sessionId={} closed={} eof={} failed={} channelConnected={}",
                    context.sessionId,
                    context.closed.get(),
                    context.eofReached.get(),
                    context.readerFailed.get(),
                    isChannelConnected(context.channel)
            );
        }
    }

}
