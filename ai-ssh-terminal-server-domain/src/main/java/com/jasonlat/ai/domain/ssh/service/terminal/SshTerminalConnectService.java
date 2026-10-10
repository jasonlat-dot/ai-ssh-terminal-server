package com.jasonlat.ai.domain.ssh.service.terminal;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.SshConnectionAttempt;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将传输建连和终端创建合为一次可取消操作，保留原有 connect/open 接口。 */
@Slf4j
@Service
public class SshTerminalConnectService {
    private static final long RETENTION_MILLIS = 120_000;
    private final Map<String, Entry> attempts = new ConcurrentHashMap<>();
    private final ISshConnectionService connections;
    private final ISshTerminalService terminals;
    private final ISshSessionPort sshSessions;
    private final ITerminalSessionPort terminalSessions;

    public SshTerminalConnectService(ISshConnectionService connections, ISshTerminalService terminals,
                                     ISshSessionPort sshSessions, ITerminalSessionPort terminalSessions) {
        this.connections = connections;
        this.terminals = terminals;
        this.sshSessions = sshSessions;
        this.terminalSessions = terminalSessions;
    }

    public TerminalSessionEntity connect(String requestId, String connectionId, int cols, int rows) {
        Entry entry = entry(requestId, connectionId);
        if (!entry.started.compareAndSet(false, true)) {
            throw new IllegalArgumentException("连接请求已处理，请使用新的请求 ID");
        }
        try {
            return sshSessions.withConnectionLock(connectionId, () -> {
                try {
                    return entry.attempt.execute(() -> {
                        if (!connections.connect(connectionId)) {
                            entry.attempt.checkCancelled();
                            throw new IllegalStateException("连接失败，请检查主机地址、端口和认证信息");
                        }
                        entry.attempt.checkCancelled();
                        TerminalSessionEntity terminal = terminals.openTerminal(connectionId, cols, rows);
                        entry.sessionId = terminal.getSessionId();
                        return terminal;
                    });
                } catch (RuntimeException exception) {
                    if (entry.sessionId != null) terminals.closeTerminal(entry.sessionId);
                    // 建连成功但 Channel 创建前被取消时，也释放无人使用的传输。
                    if (!terminalSessions.hasActiveSessions(connectionId)) sshSessions.disconnect(connectionId);
                    throw exception;
                }
            });
        } finally {
            entry.finished = true;
            entry.expiresAt = System.currentTimeMillis() + RETENTION_MILLIS;
            // 取消可能发生在 execute 最后一次检查之后，仍须清理刚返回的独立终端。
            closeCancelledTerminal(entry);
        }
    }

    public void cancel(String requestId, String connectionId) {
        // 允许取消先于 connect 到达；短期保留取消记录，迟到的请求不能重新建立连接。
        Entry entry = entry(requestId, connectionId);
        entry.cancelled = true;
        entry.attempt.cancel();
        closeCancelledTerminal(entry);
    }

    private Entry entry(String requestId, String connectionId) {
        if (requestId == null || connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("连接请求参数不能为空");
        }
        // 校验: 不是合法UUID就抛异常, 后面的代码根本不会执行
        UUID.fromString(requestId);
        Entry entry = attempts.computeIfAbsent(requestId, ignored -> new Entry(connectionId));
        if (!entry.connectionId.equals(connectionId)) throw new IllegalArgumentException("连接请求与服务器不匹配");
        return entry;
    }

    private void closeCancelledTerminal(Entry entry) {
        if (!entry.cancelled || !entry.finished || entry.sessionId == null
                || !entry.cleanupScheduled.compareAndSet(false, true)) return;
        // 不让取消接口等待 connectionId 生命周期锁；已有终端继续独立工作。
        CompletableFuture.runAsync(() -> terminals.closeTerminal(entry.sessionId))
                .exceptionally(exception -> { log.warn("清理已取消的终端失败 sessionId={}", entry.sessionId, exception); return null; });
    }

    @Scheduled(fixedDelay = 60_000)
    public void removeExpiredAttempts() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(item -> (!item.getValue().started.get() || item.getValue().finished)
                && item.getValue().expiresAt < now);
    }

    private static final class Entry {
        private final String connectionId;
        private final SshConnectionAttempt attempt = new SshConnectionAttempt();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
        private volatile String sessionId;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile long expiresAt = System.currentTimeMillis() + RETENTION_MILLIS;

        private Entry(String connectionId) { this.connectionId = connectionId; }
    }
}
