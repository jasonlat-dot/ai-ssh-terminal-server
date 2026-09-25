package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某个页签独立终端的连接状态。
 * 页签必须使用自己的 terminalSessionId 查询，不能用共享 connectionId 推断。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalConnectionStateDTO {

    /** 页签持有的终端会话 ID。 */
    private String sessionId;

    /** 该终端使用的 SSH 连接配置 ID。 */
    private String connectionId;

    /** 该 terminalSessionId 对应的 Shell Channel 是否仍然有效。 */
    private boolean connected;

    /** 已断开终端的结束原因；连接正常时为空。 */
    private String disconnectReason;

    /** 前端是否可以针对当前断开原因执行有限次数自动重连。 */
    private boolean reconnectAllowed;
}
