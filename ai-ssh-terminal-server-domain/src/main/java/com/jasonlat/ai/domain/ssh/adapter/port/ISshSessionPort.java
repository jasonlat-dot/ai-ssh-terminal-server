package com.jasonlat.ai.domain.ssh.adapter.port;

import com.jcraft.jsch.Session;

import java.util.Set;

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

    /**
     * 获取当前内存中所有已注册的连接ID（用于连接监测器遍历探活）。
     * 返回的集合是当前快照，可能包含已实际断开但尚未清理的连接。
     *
     * @return 连接ID集合
     */
    Set<String> getActiveConnectionIds();
}
