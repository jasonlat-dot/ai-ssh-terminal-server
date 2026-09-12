package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.types.utils.StringUtils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author jasonlat
 * 2026-09-11  21:07
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    private final ConcurrentHashMap<String, Session> sshSessions = new ConcurrentHashMap<>(4);
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

        // 如果已经建立连接，先断开
        disconnect(connectionId);
        try {
            Session session = jsch.getSession(username, host, port);
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
            session.setTimeout(30000); // 30秒超时
            session.setConfig("ServerAliveInterval", "30");   // 每30秒发送keep-alive
            session.setConfig("ServerAliveCountMax", "3");     // 3次无响应才断开
            session.setTimeout(0); // 不设置socket超时，避免reader线程被误杀

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

            // 建立连接
            session.connect();
            sshSessions.put(connectionId, session);
            log.info("SSH连接成功 connectionId={} host={}:{} user={}", connectionId, host, port, username);
            return true;

        } catch (JSchException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}", connectionId, host, port, e.getMessage());
            return false;
        }
    }

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    @Override
    public void disconnect(String connectionId) {
        Session session = sshSessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH连接已断开 connectionId={}", connectionId);
        }
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
