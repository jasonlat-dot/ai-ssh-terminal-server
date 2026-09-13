package com.jasonlat.ai.domain.ssh.service.terminal;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SSH终端领域服务实现
 * 遵循单一职责原则，将终端会话管理委托给基础设施层
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    /** Agent 单条命令允许等待的最长时间。 */
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    /** 防止异常命令让 Agent 结果无限增长。 */
    private static final int MAX_COMMAND_OUTPUT_CHARS = 2 * 1024 * 1024;

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;

    /** 会话ID -> 终端会话实体 映射 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionPort) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionPort;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 检查SSH连接是否已建立
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        // 2. 清理同一 connectionId 的旧会话（基础设施层已关闭 channel，这里清理域层缓存）
        sessionCache.entrySet().removeIf(entry -> {
            if (connectionId.equals(entry.getValue().getConnectionId())) {
                log.info("清理旧终端会话缓存 sessionId={} connectionId={}", entry.getKey(), connectionId);
                return true;
            }
            return false;
        });

        // 3. 通过基础设施层打开终端会话
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 3. 创建并缓存会话实体
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(sessionId, entity);
        log.info("终端会话创建成功 sessionId={}", sessionId);

        return entity;
    }

    @Override
    public String executeCommand(String sessionId, String command) throws InterruptedException {
        // 1. 校验会话
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("SSH 命令不能为空");
        }

        /*
         * 一个 terminalSession 同一时刻只执行一条 Agent 命令。
         * 这样每条命令的开始/结束标记和输出不会交叉。
         */
        synchronized (entity) {
            String token = UUID.randomUUID().toString();
            String startMarker = "\u001eSSH_AGENT_START_" + token + "\u001f";
            String endMarkerPrefix = "\u001eSSH_AGENT_END_" + token + ":";

            /*
             * Shell 会回显发送进去的命令文本，因此不能使用普通字符串作为边界。
             * printf 输出的 RS/US 控制字符不会出现在命令回显中，可以准确区分：
             *   历史/MOTD 输出、命令回显、真实命令输出、后续 prompt。
             */
            String shellCommand = "printf '\\036SSH_AGENT_START_" + token + "\\037\\n'; "
                    + "eval '" + escapeForSingleQuotedShell(command) + "'; "
                    + "__ssh_agent_exit_code=$?; "
                    + "printf '\\n\\036SSH_AGENT_END_" + token + ":%s\\037\\n' \"$__ssh_agent_exit_code\"\r";

            terminalSessionService.write(sessionId, shellCommand);
            entity.touch();

            StringBuilder received = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);

            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IllegalStateException("SSH 命令执行超时（" + COMMAND_TIMEOUT_SECONDS + " 秒）");
                }

                CompletableFuture<TerminalReadResult> readFuture = terminalSessionService.readAsync(sessionId);
                TerminalReadResult readResult;
                try {
                    // get() 会阻塞当前 Agent 调用线程，但不会阻塞 SSH Reader 线程。
                    readResult = readFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    readFuture.cancel(false);
                    throw new IllegalStateException("SSH 命令执行超时（" + COMMAND_TIMEOUT_SECONDS + " 秒）", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("读取 SSH 命令输出失败", e.getCause());
                }

                switch (readResult.getStatus()) {
                    case DATA -> {
                        if (readResult.isBufferOverflow()) {
                            throw new IllegalStateException("SSH 命令输出超过缓冲区上限，无法保证结果完整");
                        }
                        if (readResult.isHasData()) {
                            received.append(readResult.getData());
                        }
                        if (received.length() > MAX_COMMAND_OUTPUT_CHARS) {
                            throw new IllegalStateException("SSH 命令输出超过 Agent 可接收上限");
                        }

                        String completeOutput = extractCompletedCommandOutput(
                                received, startMarker, endMarkerPrefix
                        );
                        if (completeOutput != null) {
                            log.debug("命令执行完成 sessionId={} outputLength={}",
                                    sessionId, completeOutput.length());
                            return completeOutput;
                        }
                    }
                    case TIMEOUT -> {
                        // Long Poll 超时只表示本轮没有数据，命令仍可能正在运行。
                    }
                    case REPLACED -> throw new IllegalStateException(
                            "SSH 命令输出读取被同一终端会话的另一个 Long Poll 请求替换"
                    );
                    case DISCONNECTED -> throw new IllegalStateException("SSH 在命令执行期间断开");
                    case READER_ERROR -> throw new IllegalStateException("SSH Reader 在命令执行期间异常");
                }
            }
        }
    }

    private String escapeForSingleQuotedShell(String command) {
        return command.replace("'", "'\"'\"'");
    }

    /**
     * 只有同时收到开始和结束标记才返回结果；否则返回 null 继续 Long Poll。
     */
    private String extractCompletedCommandOutput(StringBuilder received,
                                                 String startMarker,
                                                 String endMarkerPrefix) {
        int start = received.indexOf(startMarker);
        if (start < 0) {
            return null;
        }

        int outputStart = start + startMarker.length();
        int end = received.indexOf(endMarkerPrefix, outputStart);
        if (end < 0) {
            return null;
        }

        int exitCodeStart = end + endMarkerPrefix.length();
        int markerEnd = received.indexOf("\u001f", exitCodeStart);
        if (markerEnd < 0) {
            return null;
        }

        String output = received.substring(outputStart, end);
        String exitCode = received.substring(exitCodeStart, markerEnd).trim();
        output = trimBoundaryLineBreaks(output);

        if (!"0".equals(exitCode)) {
            if (!output.isEmpty()) {
                output += System.lineSeparator();
            }
            output += "[命令退出码: " + exitCode + "]";
        }
        return output;
    }

    private String trimBoundaryLineBreaks(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '\r' || value.charAt(start) == '\n')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '\r' || value.charAt(end - 1) == '\n')) {
            end--;
        }
        return value.substring(start, end);
    }

    @Override
    public void resizeTerminal(String sessionId, int cols, int rows) {
        log.debug("调整终端大小 sessionId={} cols={} rows={}", sessionId, cols, rows);

        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }

        terminalSessionService.resize(sessionId, cols, rows);

        entity.setCols(cols);
        entity.setRows(rows);
        entity.touch();
    }

    @Override
    public TerminalSessionEntity getTerminalSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    @Override
    public void closeTerminal(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionService.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionCache.containsKey(sessionId);
    }

    @Override
    public String readTerminal(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        return terminalSessionService.read(sessionId);
    }

    @Override
    public CompletableFuture<TerminalReadResult> readTerminalAsync(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        return terminalSessionService.readAsync(sessionId);
    }

    @Override
    public void writeTerminal(String sessionId, String input) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        terminalSessionService.write(sessionId, input);
        entity.touch();
    }

}
