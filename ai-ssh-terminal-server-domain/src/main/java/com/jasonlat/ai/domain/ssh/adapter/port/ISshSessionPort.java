package com.jasonlat.ai.domain.ssh.adapter.port;

import com.jcraft.jsch.Session;

/**
 * @author jasonlat
 * 2026-09-11  21:03
 */
public interface ISshSessionPort {

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
    boolean connect(String connectionId, String host, int port,
                    String username, String password, String privateKey);

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    void disconnect(String connectionId);

    /**
     * 检查是否已连接
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    boolean isConnected(String connectionId);

    Session getSession(String connectionId);
}
