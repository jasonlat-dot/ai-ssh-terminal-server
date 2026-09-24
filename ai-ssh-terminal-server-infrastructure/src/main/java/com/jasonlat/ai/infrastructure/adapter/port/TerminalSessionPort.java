package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * 终端会话管理器
 */
@Slf4j
@Component
public class TerminalSessionPort extends TerminalSessionPortSupport implements ITerminalSessionPort  {

    /**
     * PTY 类型。
     * 浏览器一般使用 xterm.js，因此这里使用 xterm-256color。
     * 对 Vim、top、htop、less 等全屏终端程序非常重要。
     */
    private static final String PTY_TYPE = "xterm-256color";

    /**
     * 缓冲区溢出后给前端的提示。
     */
    private static final String BUFFER_OVERFLOW_MESSAGE = "\u001b[0m\r\n" + "\u001b[33m" + "[终端输出过快，部分较早的输出已被丢弃]" + "\u001b[0m\r\n";

    /**
     * SSH Channel 正常/异常断开提示。
     */
    private static final String DISCONNECTED_MESSAGE = "\u001b[0m\r\n" + "\u001b[31m" + "[SSH 连接已断开]" + "\u001b[0m\r\n";

    /**
     * SSH reader 异常停止提示。
     */
    private static final String READER_ERROR_MESSAGE = "\u001b[0m\r\n" + "\u001b[31m" + "[SSH 终端读取异常，请重新连接]" + "\u001b[0m\r\n";

    @Resource
    private SshSessionPort sshSessionService;

    /**
     * 创建终端会话。
     *
     * @param connectionId SSH 连接 ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return terminal sessionId
     */
    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        /*
         * ================================
         * 1. 为当前窗口创建独立终端
         * ================================
         * 同一个底层 SSH Session 可以同时承载多个 ChannelShell。
         * 每个浏览器页签/客户端窗口持有自己的 terminalSessionId 和 ChannelShell，
         * 新窗口不能关闭同 connectionId 下其他窗口的终端。
         */
        String sessionId = UUID.randomUUID().toString();

        /*
         * 先声明局部资源。
         *
         * 如果创建过程中失败，
         * 即使 TerminalSessionContext 尚未加入 Map，
         * 也能够正确释放资源。
         */
        ChannelShell channel = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;


        try {
            /*
             * ================================
             * 2. 获取底层 SSH Session
             * ================================
             */
            Session sshSession = sshSessionService.getSession(connectionId);
            if (sshSession == null || !sshSession.isConnected()) {
                throw new IllegalStateException("SSH 会话不可用 connectionId=" + connectionId);
            }

            /*
             * ================================
             * 3. 创建 Shell Channel
             * ================================
             */
            channel = (ChannelShell) sshSession.openChannel("shell");


            /*
             * 开启伪终端。
             * 如果不开启 PTY，
             * Vim、top、less、clear 等程序行为会异常。
             */
            channel.setPty(true);

            /*
             * 指定终端类型。
             */
            channel.setPtyType(PTY_TYPE);

            /*
             * 设置当前终端尺寸。
             *
             * 后两个参数表示像素尺寸。
             * 对 SSH 终端而言通常可以使用 0。
             */
            channel.setPtySize(cols, rows, 0, 0);

            /*
             * 获得 SSH Channel 输入输出流。
             * inputStream： Linux -> Java
             * outputStream：Java -> Linux
             */
            inputStream = channel.getInputStream();
            outputStream = channel.getOutputStream();

            /*
             * ================================
             * 4. 建立 Shell Channel
             * ================================
             */
            channel.connect(5000);

            /*
             * ================================
             * 5. 创建 Terminal 上下文
             * ================================
             */
            TerminalSessionContext context = new TerminalSessionContext(sessionId,connectionId,channel, inputStream,outputStream);

            /*
             * 保存 Terminal session。
             */
            terminalSessions.put(sessionId, context);

            /*
             * ================================
             * 6. 启动唯一 reader
             * ================================
             */
            startOutputReader(context);

            /*
             * 注意：
             * 这里不再像旧代码一样：
             * 最多等待 3 秒
             * +
             * 再 sleep 200ms
             * 因为 SSH 输出本质上应该完全异步。
             * openTerminal() 创建成功后直接返回 sessionId，
             * 后台 reader 会不断把 MOTD / shell prompt
             * 放入 outputBuffer。
             * 前端下一次 read() 即可读取。
             */
            log.info("终端会话打开成功 sessionId={} connectionId={} size={}x{}", sessionId, connectionId, cols, rows);
            return sessionId;

        } catch (Exception e) {
            log.error("打开终端会话失败 connectionId={} sessionId={}", connectionId, sessionId, e);

            /*
             * 如果 context 已经成功加入 terminalSessions，
             * 使用统一 cleanup 清理。
             */
            if (terminalSessions.containsKey(sessionId)) {
                cleanup(sessionId);
            } else {
                /*
                 * context 还没有放进去时，
                 * cleanup(sessionId) 找不到资源。
                 * 所以手动释放当前局部变量。
                 */
                closeQuietly(outputStream);
                closeQuietly(inputStream);
                disconnectQuietly(channel);
            }
            throw new RuntimeException("打开终端失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean hasActiveSessions(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        return terminalSessions.values().stream().anyMatch(context ->
                connectionId.equals(context.connectionId)
                        && !context.closed.get()
                        && isChannelConnected(context.channel));
    }


    /**
     * 向 SSH Terminal 写入数据。
     * 注意：
     * 这里没有再做 IOException 自动重试。
     * 原因是：
     * OutputStream.write()
     * 如果发生 IOException，
     * Java 无法确认到底已经成功写入了多少字节。
     * 如果直接把整个 command 再发送一次，
     * 有可能造成：
     * ls 实际变成：lsls
     * 因此终端这种场景下，不建议自动重发用户输入。
     */
    @Override
    public void write(String sessionId, String command) {

        TerminalSessionContext context = getTerminalSession(sessionId);
        if (!isChannelConnected(context.channel)) {
            log.warn("write - SSH Channel 已断开 sessionId={}", sessionId);
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        if (command == null || command.isEmpty()) {
            return;
        }
        /*
         * 同一个 Terminal 可能存在多个 Web 请求同时 write。
         * OutputStream 本身不能保证：
         * request A 写入：
         * abc
         * request B 写入：
         * 123
         * 一定不会变成：
         * a12bc3
         * 所以增加独立 writeLock。
         */
        // 优化：锁内只保留IO操作
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        synchronized (context.writeLock) {
            try {
                context.outputStream.write(bytes);
                /*
                 * Terminal 输入应该立即发送，
                 * 所以这里主动 flush。
                 */
                context.outputStream.flush();

            } catch (IOException e) {
                log.error("写入终端失败 sessionId={} reason={}", sessionId, e.getMessage(), e);
                throw new RuntimeException("写入终端失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public String executeCommand(String sessionId, String command, long timeoutSeconds) throws InterruptedException {
        /*
         * 先验证上下文和 Channel。这里不能只判断 terminalSessions 中是否存在 sessionId，
         * 因为远端已经断开时 Context 可能还没来得及被 Reader 清理。
         */
        TerminalSessionContext context = getTerminalSession(sessionId);
        if (!isChannelConnected(context.channel)) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("SSH 命令不能为空");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("SSH 命令超时时间必须大于 0");
        }

        /*
         * 一个交互式 Shell 只有一条输入流和一条输出流。两个 Agent 命令并发执行时，
         * 输出会交叉，开始/结束标记也可能互相嵌套，所以同一终端必须串行执行 Agent 命令。
         * 这个锁只限制 Agent 工具调用，不会停止 SSH Reader，也不会暂停前端 Long Poll。
         */
        synchronized (context.agentCommandLock) {
            // synchronized 等锁期间不会因 interrupt 自动退出；进入后再次检查，
            // 防止已停止的排队命令仍被写进远端 Shell。
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Agent 命令已取消");
            }
            /* 每条命令使用独立 UUID，避免命令正文或历史终端输出意外命中边界。 */
            String token = UUID.randomUUID().toString();
            AgentCommandCapture capture = new AgentCommandCapture(
                    "\u001eSSH_AGENT_START_" + token + "\u001f",
                    "\u001eSSH_AGENT_END_" + token + ":",
                    command
            );

            /*
             * 必须先注册捕获器再写命令。顺序反过来时，响应很快的命令可能在捕获器注册
             * 之前就已返回，从而永久错过开始标记。
             */
            synchronized (context.agentCaptureLock) {
                context.activeAgentCommand = capture;
            }

            /*
             * 在原有交互式 Shell 中执行命令，而不是另开 exec Channel：
             * 这样 Agent 可以继承用户在终端里执行 cd、export 等操作后形成的环境。
             *
             * RS(\036) 和 US(\037) 包住边界；结束标记同时携带 $?，让上层既得到输出，
             * 也能知道命令是否成功。eval 的参数经过单引号转义，避免破坏包装脚本结构。
             */
//            String shellCommand = "printf '\\036SSH_AGENT_START_" + token + "\\037\\n'; "
//                    + "eval '" + escapeForSingleQuotedShell(command) + "'; "
//                    + "__ssh_agent_exit_code=$?; "
//                    + "printf '\\n\\036SSH_AGENT_END_" + token + ":%s\\037\\n' \"$__ssh_agent_exit_code\"\r";
            String shellCommand = "printf '\\036SSH_AGENT_START_" + token + "\\037\\n'; "
                    + "set -o pipefail; "
                    + "eval '" + escapeForSingleQuotedShell(command) + "'; "
                    + "__ssh_agent_exit_code=$?; "
                    + "printf '\\n\\036SSH_AGENT_END_" + token + ":%s\\037\\n' \"$__ssh_agent_exit_code\"\n";


            boolean commandSent = false;
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Agent 命令已取消");
                }
                write(sessionId, shellCommand);
                commandSent = true;
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Agent 命令已取消");
                }

                /*
                 * 当前线程等待的是 AgentCommandCapture.result，不是 readAsync()。
                    - 后台 Reader 线程持续读到 SSH 输出，进入`capture.accept(incoming)`
                    - `accept`内部扫描流，匹配开始标记，开始收集输出
                    - 当 Reader 读到**结束标记 `SSH_AGENT_END_token:exitcode`**
                      - 解析 exitCode
                      - 把收集好的命令输出，调用 `capture.result.complete(最终文本)`
                      - Future 完成 → **主线程的 get () 唤醒，拿到返回字符串**
                 */
                return capture.result.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                // 仅中断本地等待并不会终止远端 Shell；必须向当前命令发送 Ctrl+C。
                // 临时清除本线程的中断位，确保 SSH 写入不会因已中断而被拒绝；下方再恢复。
                Thread.interrupted();
                if (commandSent && !capture.result.isDone()) {
                    capture.result.completeExceptionally(interrupted);
                    try {
                        write(sessionId, "\u0003");
                    } catch (Exception interruptError) {
                        log.warn("取消 Agent 命令时发送 Ctrl+C 失败 sessionId={}", sessionId, interruptError);
                    }
                }
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (TimeoutException e) {
                /*
                 * 到达命令级总超时后先让 Agent Future 失败，再向远端发送 Ctrl+C，尽量终止
                 * 仍在运行的命令。write() 失败不能覆盖原始“命令超时”异常。
                 */
                capture.result.completeExceptionally(new IllegalStateException("SSH 命令执行超时（" + timeoutSeconds + " 秒）"));
                try {
                    write(sessionId, "\u0003");
                } catch (Exception interruptError) {
                    log.debug("命令超时后发送 Ctrl+C 失败 sessionId={}", sessionId, interruptError);
                }
                throw new IllegalStateException("SSH 命令执行超时（" + timeoutSeconds + " 秒）", e);
            } catch (ExecutionException e) {
                // Reader EOF、Reader 异常、缓冲区溢出会通过 Future 的 cause 传递到这里。
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("读取 SSH 命令输出失败", cause);
            } finally {
                /*
                 * 只清理自己创建的捕获器。虽然 agentCommandLock 已避免 Agent 并发，仍保留
                 * 身份判断，防止关闭流程已经先一步把 activeAgentCommand 清空。
                 */
                synchronized (context.agentCaptureLock) {
                    if (context.activeAgentCommand == capture) {
                        capture.completed = true;
                        context.activeAgentCommand = null;
                    }
                }
            }
        }
    }

    private String escapeForSingleQuotedShell(String command) {
        /* POSIX Shell 中要在单引号字符串里表达单引号，需要结束引号、写入 '、再重新开启。 */
        return command.replace("'", "'\"'\"'");
    }


    /**
     * 读取当前未消费的 Terminal 输出。
     * 本方法完全非阻塞。
     * 有数据：立即返回数据
     * 没数据：返回 ""
     * 前端自己通过 polling / WebSocket 控制读取频率。
     */
    @Override
    public String read(String sessionId) {
        TerminalSessionContext context = getTerminalSession(sessionId);
        /*
         * ================================
         * 1. 读取当前 buffer
         * ================================
         */
        String output = null;
        synchronized (context.eventLock) {
            if (!context.outputBuffer.isEmpty()) {
                output = context.outputBuffer.toString();
                /*
                 * 数据已经被前端消费，
                 * 清空缓冲区。
                 */
                context.outputBuffer.setLength(0);
            }
        }

        /*
         * ================================
         * 2. 如果之前发生 Buffer Overflow
         * ================================
         */
        boolean overflow = context.bufferOverflowed.getAndSet(false);

        if (output != null && !output.isEmpty()) {
            if (overflow) {
                return BUFFER_OVERFLOW_MESSAGE + output;
            }
            return output;
        }

        /*
         * buffer 已经没有数据，
         * 但如果之前发生 overflow，
         * 仍然需要通知前端。
         */
        if (overflow) {
            return BUFFER_OVERFLOW_MESSAGE;
        }

        /*
         * ================================
         * 3. reader 异常
         * ================================
         * 如果 Channel 仍然连接，
         * 但是 reader 因 IOException 停止，
         * 说明当前 Terminal 已经不可靠。
         * 不再尝试启动第二个 reader。
         * 因为多个 reader 读取同一个 InputStream
         * 会引发更严重的数据竞争。
         */

        if (context.readerFailed.get()) {
            if (context.stateMessageReturned.compareAndSet(false, true)) {
                return READER_ERROR_MESSAGE;
            }
            return "";
        }


        /*
         * ================================
         * 4. SSH 已经断开
         * ================================
         */
        boolean disconnected = context.eofReached.get() || !isChannelConnected(context.channel);
        if (disconnected) {
            /*
             * 只返回一次断开提示。
             * 避免前端每轮 polling 都出现：
             * [SSH连接已断开]
             * [SSH连接已断开]
             * [SSH连接已断开]
             */
            if (context.stateMessageReturned.compareAndSet(false, true)) {
                return DISCONNECTED_MESSAGE;
            }
        }

        /*
         * 当前没有任何新数据。
         */
        return "";
    }



    /**
     * 异步读取 SSH 数据。
     * ----------------------------------------------------------
     * 情况一：
     * outputBuffer 已经有数据
     *      ↓
     * 立即返回 completedFuture
     * ----------------------------------------------------------
     * 情况二：
     * 当前没有 SSH 数据
     *      ↓
     * 创建 CompletableFuture
     *      ↓
     * 放入 context.pendingRead
     *      ↓
     * 当前请求暂停
     * ----------------------------------------------------------
     * 之后 SSH Reader 收到数据：
     * pendingRead.complete(...)
     *      ↓
     * Controller 的 DeferredResult 返回
     */
    @Override
    public CompletableFuture<TerminalReadResult> readAsync(String sessionId) {
        TerminalSessionContext context = getTerminalSession(sessionId);
        /*
         * 如果前端错误地同时创建两个 Long Poll，我们使用最新请求替换旧请求。
         */
        CompletableFuture<TerminalReadResult> oldPending = null;
        CompletableFuture<TerminalReadResult> future;

        synchronized (context.eventLock) {
            /*
             * ==========================================
             * 1. Buffer 已经存在未消费的数据
             * ==========================================
             */
            if (!context.outputBuffer.isEmpty()) {
                String data = consumeOutputBuffer(context);

                /*
                 * 获取并清除 Buffer Overflow 状态。只通知前端一次。
                 */
                boolean bufferOverflow = context.bufferOverflowed.getAndSet(false);
                return CompletableFuture.completedFuture(TerminalReadResult.data(data, isChannelConnected(context.channel), bufferOverflow));
            }

            /*
             * ==========================================
             * 2. SSH Reader 已经异常退出
             * ==========================================
             */
            if (context.readerFailed.get()) {
                return CompletableFuture.completedFuture(
                        TerminalReadResult.readerError(
                                isChannelConnected(context.channel)
                        )
                );
            }

            /*
             * ==========================================
             * 3. SSH 已经 EOF 或真正断开
             * ==========================================
             */
            if (context.eofReached.get() || !isChannelConnected(context.channel)) {
                return CompletableFuture.completedFuture(
                        TerminalReadResult.disconnected(
                                context.eofReached.get()
                        )
                );
            }

            /*
             * ==========================================
             * 4. 是否存在上一个 Long Poll
             * ==========================================
             * 正常情况下不会出现。
             * 但是例如：浏览器网络卡顿 前端重复调用 React 重复 effect 都可能造成并发 Long Poll。
             */
            if (context.pendingRead != null && !context.pendingRead.isDone()) {
                oldPending = context.pendingRead;
            }

            /*
             * ==========================================
             * 5. 创建新的 Long Poll
             * ==========================================
             */
            future = new CompletableFuture<>();
            context.pendingRead = future;
        }

        /*
         * 不要在 synchronized(eventLock) 内 complete Future。
         *
         * Future.complete() 可能触发 callback，
         * callback 又可能调用其他 Terminal 方法，
         * 会让锁关系变复杂。
         */
        if (oldPending != null) {
            oldPending.complete(
                    TerminalReadResult.replaced(
                            isChannelConnected(context.channel)
                    )
            );
        }

        /*
         * ==========================================
         * 6. Long Poll 超时
         * ==========================================
         * 这里不能把 timeout 理解成：SSH 命令结束。
         * 它仅仅表示：这 25 秒内没有新的 SSH Terminal 输出。
         */
        CompletableFuture.delayedExecutor( LONG_POLL_TIMEOUT_SECONDS,TimeUnit.SECONDS).execute(() -> {
            if (future.isDone()) {
                return;
            }
            future.complete(
                    TerminalReadResult.timeout(
                            isChannelConnected(context.channel)
                    )
            );
        });

        /*
         * ==========================================
         * 7. Long Poll 完成后清除引用
         * ==========================================
         * DATA
         * TIMEOUT
         * DISCONNECTED
         * READER_ERROR
         * REPLACED
         * 无论哪种状态完成，都需要清理。
         */
        future.whenComplete(
                (result, throwable) -> {
                    synchronized (context.eventLock) {
                        /*
                         * 一定要判断是不是当前 Future。
                         * 防止：oldFuture 完成却把 newFuture 的 pendingRead 清除了。
                         */
                        if (context.pendingRead == future) {
                            context.pendingRead = null;
                        }
                    }
                }
        );
        return future;
    }



    /**
     * 调整 Terminal 尺寸。
     * xterm.js resize 时可以调用这里。
     */
    @Override
    public void resize(String sessionId, int cols, int rows) {
        TerminalSessionContext context = getTerminalSession(sessionId);
        if (!isChannelConnected(context.channel)) {
            log.warn("resize - Terminal 已关闭 sessionId={}", sessionId);
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }

        /*
         * 防止前端传入非法尺寸。
         */
        if (cols <= 0 || rows <= 0) {
            log.warn("非法终端尺寸 sessionId={} cols={} rows={}", sessionId, cols, rows);
            return;
        }

        try {
            context.channel.setPtySize(cols, rows, 0, 0);
            log.debug("终端大小调整成功 sessionId={} {}x{}", sessionId, cols, rows);

        } catch (Exception e) {
            log.error("调整终端大小失败 sessionId={} {}x{}", sessionId, cols, rows, e);
            throw new RuntimeException("调整终端大小失败: " + e.getMessage(), e);
        }
    }


    /**
     * 主动关闭 Terminal。
     */
    @Override
    public void closeSession(String sessionId) {
        log.info("主动关闭终端会话 sessionId={}", sessionId);
        cleanup(sessionId);
    }


    /**
     * 判断 Terminal 是否仍然存在并且 SSH Channel 正常连接。
     */
    @Override
    public boolean sessionExists(String sessionId) {
        TerminalSessionContext context = terminalSessions.get(sessionId);
        return context != null && !context.closed.get() && isChannelConnected(context.channel);
    }

}
