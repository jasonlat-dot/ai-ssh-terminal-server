package com.jasonlat.ai.domain.ssh.model.valobj;

import lombok.Getter;

/**
 * 终端结束原因。前端根据 reconnectAllowed 区分临时网络故障和服务端策略关闭。
 */
@Getter
public enum TerminalDisconnectReason {

    /** 超过配置的无交互时间，由后端主动回收。 */
    IDLE_TIMEOUT(false),

    /** 用户主动关闭终端。 */
    CLIENT_CLOSED(false),

    /** SSH Channel/网络连接断开，可以进行有限次数自动重连。 */
    CHANNEL_DISCONNECTED(true),

    /** SSH 输出 Reader 异常退出，可以进行有限次数自动重连。 */
    READER_ERROR(true),

    /** 后端没有该 sessionId，例如终止记录已过期或服务刚重启。 */
    SESSION_NOT_FOUND(false);

    private final boolean reconnectAllowed;

    TerminalDisconnectReason(boolean reconnectAllowed) {
        this.reconnectAllowed = reconnectAllowed;
    }

}
