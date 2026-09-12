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
            if (len >= MAX_OUTPUT_BUFFER_SIZE) {
                context.outputBuffer.setLength(0);
                /*
                 * 只保留这一批数据的最后 MAX_SIZE 部分。
                 */
                int start = len - MAX_OUTPUT_BUFFER_SIZE;
                context.outputBuffer.append(chars, start, MAX_OUTPUT_BUFFER_SIZE);
                context.bufferOverflowed.set(true);
            } else {
                /*
                 * Buffer 放不下新数据。
                 */
                if (currentSize + len > MAX_OUTPUT_BUFFER_SIZE) {
                    /*
                     * 丢弃旧数据，优先保留最新 Terminal 输出。
                     * 对 Terminal 场景来说：保证 JVM 不 OOM 比无限保存所有历史输出更加重要。
                     */
                    context.outputBuffer.setLength(0);

                    context.bufferOverflowed.set(true);
                    log.warn("Terminal输出缓冲区溢出，丢弃部分旧数据 sessionId={} oldSize={} incomingSize={} maxSize={}",
                            context.sessionId, currentSize, len, MAX_OUTPUT_BUFFER_SIZE
                    );
                }
                /*
                 * 保存本次 SSH 输出。
                 */
                context.outputBuffer.append(chars, 0, len);
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
