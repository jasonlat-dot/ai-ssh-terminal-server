package com.jasonlat.ai.domain.ssh.adapter.port;

import com.jcraft.jsch.Session;

/**
 * SSH 底层传输会话端口。
 * <p>
 * 一个 {@code connectionId} 对应一个可复用的 JSch {@link Session}，代表到远端服务器的
 * TCP/SSH 连接；浏览器窗口使用的交互终端不在这里创建，而是由
 * {@link ITerminalSessionPort} 在该 Session 上分别打开独立 Shell Channel。
 * 因此“同一连接打开多个终端”并不等于重复建立多条底层 SSH 连接。
 *
 * @author jasonlat
 * 2026-09-11  21:03
 */
public interface ISshSessionPort {

    /**
     * 建立或复用 SSH 连接。同一 connectionId 已存在健康 Session 时应直接复用，
     * 不能为了新窗口重新建连并关闭旧 Session。
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

    /** 获取供终端层创建多个 Shell Channel 的共享底层 Session。 */
    Session getSession(String connectionId);

}
