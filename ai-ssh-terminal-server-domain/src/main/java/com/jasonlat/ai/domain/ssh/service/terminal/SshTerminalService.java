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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH终端领域服务实现
 * 遵循单一职责原则，将终端会话管理委托给基础设施层
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

        // 2. 通过基础设施层打开独立终端会话。同一个 connectionId 可以同时对应多个窗口。
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

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

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionService.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
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
