package com.jasonlat.ai.domain.ssh.model.valobj;

import lombok.Builder;
import lombok.Value;

/**
 * 已结束终端的短期记录，用于前端错过 Long Poll 响应后查询真实断开原因。
 */
@Value
@Builder
public class TerminalTermination {

    String sessionId;
    String connectionId;
    TerminalDisconnectReason reason;
    long terminatedAtMillis;

    public boolean isReconnectAllowed() {
        return reason != null && reason.isReconnectAllowed();
    }
}
