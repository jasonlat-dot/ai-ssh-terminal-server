package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.types.utils.StringUtils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author jasonlat
 * 2026-09-11  21:07
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    /** 建立 TCP/SSH 连接最多等待 30 秒。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    /**
     * SSH 心跳间隔。JSch 通过 Socket 读超时触发 SSH_MSG_GLOBAL_REQUEST，
     * 因此必须使用 setServerAliveInterval，不能只写入同名 config。
     */
    private static final int SERVER_ALIVE_INTERVAL_MILLIS = 30_000;

    /** 连续 5 次心跳无响应才判定连接失效，容忍海外链路的短时抖动。 */
    private static final int SERVER_ALIVE_COUNT_MAX = 5;

    private final ConcurrentHashMap<String, Session> sshSessions = new ConcurrentHashMap<>(4);
    /** 同一 connectionId 的建连、复用和断开必须串行，避免两个窗口同时重建连接。 */
    private final ConcurrentHashMap<String, Object> connectionLocks = new ConcurrentHashMap<>(4);
    private final JSch jsch = new JSch();


    /**
     * 建立 SSH 连接
     *
     * @param connectionId 连接ID
     * @param host         主机地址
     * @param port         端口
     * @param username     用户名
     * @param password     密码
     * @param privateKey   私钥
     * @return 是否连接成功
     */
    @Override
    public boolean connect(String connectionId, String host, int port, String username, String password, String privateKey) {
        Object connectionLock = connectionLocks.computeIfAbsent(connectionId, ignored -> new Object());
        synchronized (connectionLock) {
            Session previous = sshSessions.get(connectionId);
            if (previous != null && previous.isConnected()) {
                log.info("SSH连接复用 connectionId={} host={}:{} user={}", connectionId, host, port, username);
                return true;
            }

            boolean reconnect = previous != null;
            long startNanos = System.nanoTime();
            log.info("SSH{}开始 connectionId={} host={}:{} user={} connectTimeoutMs={} keepAliveIntervalMs={} keepAliveMaxMisses={}",
                    reconnect ? "重连" : "连接",
                    connectionId,
                    host,
                    port,
                    username,
                    CONNECT_TIMEOUT_MILLIS,
                    SERVER_ALIVE_INTERVAL_MILLIS,
                    SERVER_ALIVE_COUNT_MAX);

            // 仅清理已经失效的旧 Session；健康 Session 会被多个窗口共同复用。
            closeSession(connectionId, previous);
            Session session = null;
            try {
                session = jsch.getSession(username, host, port);
            /*
             * SSH 原生机制：第一次连接服务器，服务器会返回 host‑key（主机公钥指纹）。
             * - `StrictHostKeyChecking`：主机密钥严格校验策略
             *   - `yes`：必须本地 known_hosts 文件存在该服务器指纹，否则直接拒绝连接（生产安全默认）
             *   - `no`：不校验服务器主机指纹，自动跳过 known_hosts 检查，不做服务端身份校验
             *   - `ask`：控制台弹窗询问是否信任主机（程序后台运行会卡死）
             */
            // jsch.setKnownHosts("./known_hosts");
            // session.setConfig("StrictHostKeyChecking", "no");

            session.setConfig("StrictHostKeyChecking", "no");

            if (StringUtils.isNotBlank(privateKey)) {
                // 私钥验证
                jsch.addIdentity(connectionId, privateKey.getBytes(StandardCharsets.UTF_8), null, null);
            } else if (StringUtils.isNotBlank(password)) {
                // 密码验证
                session.setPassword(password);
            } else {
                log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
                return false;
            }

            // connect(int) 只控制建连阶段，避免网络不可达时无限等待。
            session.connect(CONNECT_TIMEOUT_MILLIS);

            /*
             * 必须在连接成功后调用 JSch 的专用 API。setServerAliveInterval 会为
             * Socket 设置读超时，并在空闲超时后发送 SSH 心跳；不能再 setTimeout(0)，
             * 否则空闲连接不会触发心跳。普通空闲不会导致 Terminal reader 退出。
             */
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MILLIS);
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);

            sshSessions.put(connectionId, session);
            log.info("SSH{}成功 connectionId={} host={}:{} user={} durationMs={} keepAliveIntervalMs={} keepAliveMaxMisses={}",
                    reconnect ? "重连" : "连接",
                    connectionId,
                    host,
                    port,
                    username,
                    elapsedMillis(startNanos),
                    session.getServerAliveInterval(),
                    session.getServerAliveCountMax());
                return true;

            } catch (JSchException e) {
                closeSession(connectionId, session);
                log.error("SSH{}失败 connectionId={} host={}:{} durationMs={} error={}",
                        reconnect ? "重连" : "连接",
                        connectionId,
                        host,
                        port,
                        elapsedMillis(startNanos),
                        e.getMessage(),
                        e);
                return false;
            }
        }
    }

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    @Override
    public void disconnect(String connectionId) {
        Object connectionLock = connectionLocks.computeIfAbsent(connectionId, ignored -> new Object());
        synchronized (connectionLock) {
            closeSession(connectionId, sshSessions.get(connectionId));
        }
    }

    private void closeSession(String connectionId, Session session) {
        if (session == null) {
            return;
        }
        sshSessions.remove(connectionId, session);
        boolean connectedBeforeClose = session.isConnected();
        try {
            if (connectedBeforeClose) {
                session.disconnect();
            }
        } finally {
            log.info("SSH底层连接已释放 connectionId={} connectedBeforeClose={}", connectionId, connectedBeforeClose);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 检查是否已连接
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    @Override
    public boolean isConnected(String connectionId) {
        Session session = sshSessions.get(connectionId);
        return session != null && session.isConnected();
    }

    /**
     * 获取会话
     *
     * @param connectionId 连接ID
     * @return JSch Session
     */
    @Override
    public Session getSession(String connectionId) {
        return sshSessions.get(connectionId);
    }

    @Override
    public Set<String> getActiveConnectionIds() {
        // 返回快照，避免遍历期间并发修改
        return new HashSet<>(sshSessions.keySet());
    }
}
