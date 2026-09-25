package com.jasonlat.ai.domain.ssh.service.terminal;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalDisconnectReason;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalTermination;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SSH 终端领域服务实现。
 * <p>
 * 领域缓存和基础设施缓存都以 terminalSessionId 为键，而不是以 connectionId 为键。
 * 因而同一服务器/连接可以同时存在多个终端实体，每个终端拥有独立读写和生命周期；
 * 具体 ChannelShell 的创建与释放委托给基础设施层。
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    /**
     * Agent 单条命令允许等待的最长时间。
     * 这是“整条命令执行完成”的上限，与前端每次 Long Poll 的 25 秒等待时间无关。
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;
    private final ISshConnectionRepository connectionRepository;

    /**
     * terminalSessionId -> 终端会话实体。
     * 多个实体可以具有相同 connectionId，这是多窗口连接同一服务器的领域层基础。
     */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionPort,
                              ISshConnectionRepository connectionRepository) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionPort;
        this.connectionRepository = connectionRepository;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);
        return sshSessionService.withConnectionLock(connectionId,
                () -> openTerminalLocked(connectionId, cols, rows));
    }

    /** 配置校验、Channel 创建和领域缓存注册共享同一个 connectionId 生命周期锁。 */
    private TerminalSessionEntity openTerminalLocked(String connectionId, int cols, int rows) {
        // 1. 检查SSH连接是否已建立
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        SshConnectionEntity connection = connectionRepository.queryConnectionById(connectionId);
        if (connection == null) {
            throw new AppException(ResponseCode.CONNECTION_NOT_FOUND);
        }
        String userId = connection.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = "defaultUser";
        }

        // 2. 每次调用都创建新的 terminalSessionId + ChannelShell；只复用底层 JSch Session。
        String sessionId = terminalSessionService.openTerminal(userId, connectionId, cols, rows);

        // 3. 创建并缓存会话实体；其他窗口的会话继续保留。
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
        /*
         * 领域层先检查业务会话是否仍处于活动状态，避免把已经关闭的 sessionId 继续传给
         * JSch。基础设施层还会再次检查真实 Channel，处理网络刚好断开的竞态情况。
         */
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new AppException(ResponseCode.TERMINAL_SESSION_NOT_FOUNT);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("SSH 命令不能为空");
        }

        /*
         * 完整结果的收集由基础设施层完成。这里不再循环调用 readAsync()，所以不会与
         * 浏览器正在进行的 Long Poll 竞争同一个 pendingRead 或 outputBuffer。
         */
        String output = terminalSessionService.executeCommand(sessionId, command, COMMAND_TIMEOUT_SECONDS);

        // 只有命令正常返回时才刷新会话最后活动时间；异常由上层工具转换成失败结果。
        entity.touch();
        log.debug("命令执行完成 sessionId={} outputLength={}", sessionId, output.length());
        return output;
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

        /*
         * 先关闭当前页签自己的 ChannelShell，再检查共享的底层 SSH Session 是否仍被
         * 其他页签使用。这样前端只需调用一次 close，无需再额外调用 disconnect。
         */
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null) {
            return;
        }

        String connectionId = entity.getConnectionId();
        sshSessionService.withConnectionLock(connectionId, () -> {
            if (!sessionCache.remove(sessionId, entity)) {
                return;
            }
            terminalSessionService.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);

            if (!terminalSessionService.hasActiveSessions(connectionId)) {
                sshSessionService.disconnect(connectionId);
                log.info("连接已无活动终端，释放底层SSH连接 connectionId={}", connectionId);
            }
        });
    }

    @Override
    public boolean sessionExists(String sessionId) {
        // 先查域层缓存，再委托基础设施层校验 channel 真实连通性
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            return false;
        }
        return terminalSessionService.sessionExists(sessionId);
    }

    @Override
    public TerminalTermination getTerminalTermination(String sessionId) {
        return terminalSessionService.getTermination(sessionId);
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
            TerminalTermination termination = terminalSessionService.getTermination(sessionId);
            TerminalDisconnectReason reason = termination == null
                    ? TerminalDisconnectReason.SESSION_NOT_FOUND : termination.getReason();
            return CompletableFuture.completedFuture(TerminalReadResult.disconnected(false, reason));
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

    /**
     * 同步清理基础设施资源和领域缓存，避免浏览器异常退出后留下无主终端。
     */
    @Scheduled(
            fixedDelayString = "${ai.ssh.terminal.cleanup-interval-minutes:5}",
            initialDelayString = "${ai.ssh.terminal.cleanup-interval-minutes:5}",
            timeUnit = TimeUnit.MINUTES
    )
    public void cleanupInactiveTerminals() {
        List<String> cleanedSessionIds = terminalSessionService.cleanupInactiveSessions();
        Set<String> affectedConnectionIds = new HashSet<>();
        cleanedSessionIds.forEach(sessionId -> {
            TerminalSessionEntity entity = sessionCache.remove(sessionId);
            if (entity != null) {
                affectedConnectionIds.add(entity.getConnectionId());
            }
        });

        /* 最后一个终端被定时回收后，同时释放不再承载 Channel 的底层 SSH Session。 */
        affectedConnectionIds.forEach(connectionId -> {
            sshSessionService.withConnectionLock(connectionId, () -> {
                if (!terminalSessionService.hasActiveSessions(connectionId)) {
                    sshSessionService.disconnect(connectionId);
                }
            });
        });
        if (!cleanedSessionIds.isEmpty()) {
            log.info("终端定时清理完成 count={}", cleanedSessionIds.size());
        }
    }

}
