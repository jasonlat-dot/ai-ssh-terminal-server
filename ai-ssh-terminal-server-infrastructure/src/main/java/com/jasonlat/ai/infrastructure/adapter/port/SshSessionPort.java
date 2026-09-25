package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.infrastructure.config.SshHttpProxyProperties;
import com.jasonlat.ai.types.utils.StringUtils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JSch 底层 SSH Session 管理器。
 * <p>
 * 映射关系为 {@code connectionId -> Session}。一个健康 Session 可以承载多个
 * ChannelShell；终端窗口的隔离由 {@link TerminalSessionPort} 管理，本类不感知窗口数量。
 *
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

    /** 固定数量的分段锁避免为每个历史 connectionId 永久保留一个锁对象。 */
    private static final int CONNECTION_LOCK_STRIPES = 64;

    /**
     * 每个连接配置只保留一条底层 SSH 传输；同一服务器若保存为不同 connectionId，
     * 仍会按不同配置建立独立 Session。
     */
    private final ConcurrentHashMap<String, Session> sshSessions = new ConcurrentHashMap<>(4);
    /** 相同 connectionId 总会映射到同一个固定锁；锁数量不会随历史连接数增长。 */
    private final Object[] connectionLocks = createConnectionLocks();
    /** 全局 HTTP CONNECT 代理配置；所有 SSH Session 使用同一代理出口。 */
    private final SshHttpProxyProperties httpProxyProperties;

    /** Spring 运行时使用配置化构造方法。 */
    @Autowired
    public SshSessionPort(SshHttpProxyProperties httpProxyProperties) {
        this.httpProxyProperties = httpProxyProperties;
    }

    /** 保留给手工测试使用；默认关闭代理并保持原来的直连行为。 */
    public SshSessionPort() {
        this(new SshHttpProxyProperties());
    }

    private static Object[] createConnectionLocks() {
        Object[] locks = new Object[CONNECTION_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object connectionLock(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId不能为空");
        }
        return connectionLocks[(connectionId.hashCode() & Integer.MAX_VALUE) % connectionLocks.length];
    }

    @Override
    public <T> T withConnectionLock(String connectionId, Supplier<T> action) {
        synchronized (connectionLock(connectionId)) {
            return action.get();
        }
    }

    @Override
    public void withConnectionLock(String connectionId, Runnable action) {
        synchronized (connectionLock(connectionId)) {
            action.run();
        }
    }


    /**
     * 建立或复用 SSH 连接。该方法可能被多个窗口同时调用，connectionLock 保证同一
     * connectionId 最多只有一个线程执行真实建连，其余线程会看到并复用已连接 Session。
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
        return withConnectionLock(connectionId, () -> {
            Session previous = sshSessions.get(connectionId);
            // 健康 Session 上可以继续 openChannel("shell")，不能因新窗口接入而替换它。
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
                /*
                 * 每条底层 SSH Session 使用独立 JSch/IdentityRepository。Session 断开并从
                 * sshSessions 删除后，私钥 Identity 会随该对象图一起回收，不会沉积在全局实例中。
                 */
                JSch connectionJsch = new JSch();
                session = connectionJsch.getSession(username, host, port);
                configureHttpProxy(session, connectionId);
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
                connectionJsch.addIdentity(connectionId, privateKey.getBytes(StandardCharsets.UTF_8), null, null);
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
        });
    }

    /**
     * 在 SSH 握手前为 Session 设置 HTTP CONNECT 代理。代理只负责建立到目标
     * {@code host:port} 的 TCP 隧道，后续认证、心跳和 Channel 都运行在同一隧道中。
     */
    private void configureHttpProxy(Session session, String connectionId) {
        if (!httpProxyProperties.isEnabled()) {
            return;
        }

        ProxyHTTP proxy = new ProxyHTTP(httpProxyProperties.getHost(), httpProxyProperties.getPort());
        if (StringUtils.isNotBlank(httpProxyProperties.getUsername())) {
            proxy.setUserPasswd(httpProxyProperties.getUsername(), httpProxyProperties.getPassword());
        }
        session.setProxy(proxy);
        log.info("SSH HTTP代理已配置 connectionId={} proxy={}:{} authentication={}",
                connectionId,
                httpProxyProperties.getHost(),
                httpProxyProperties.getPort(),
                StringUtils.isNotBlank(httpProxyProperties.getUsername()));
    }

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    @Override
    public void disconnect(String connectionId) {
        withConnectionLock(connectionId, () -> closeSession(connectionId, sshSessions.get(connectionId)));
    }

    /**
     * 释放底层传输 Session。调用前必须由领域层确认该 connectionId 已无活动终端；
     * 单个窗口关闭只应清理自己的 ChannelShell，不应直接进入此方法。
     */
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

}
