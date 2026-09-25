package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH 终端 Long Polling 读取结果
 * 前端不再只接收 String，
 * 而是接收终端当前完整状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminalReadResultDTO {

    /**
     * 本次返回状态。
     */
    private String status;

    /**
     * 本次 SSH 输出。
     * 没有数据时返回空字符串。
     */
    private String output;

    /**
     * 本次是否包含终端数据。
     */
    private boolean hasData;

    /**
     * SSH Channel 当前是否仍然连接。
     */
    private boolean connected;

    /**
     * 是否已经读取到 SSH EOF。
     */
    private boolean eof;

    /**
     * 本次请求是否因为 Long Polling 超时而返回。
     * timeout=true 并不代表 SSH 命令执行完成，
     * 仅表示本次 HTTP Long Poll 等待到期。
     */
    private boolean timeout;

    /**
     * SSH 输出缓存是否发生过溢出。
     * 如果前端长时间没有读取，而 SSH 输出非常多，
     * 后端会主动丢弃部分旧数据，避免 JVM OOM。
     */
    private boolean bufferOverflow;

    /** 终端断开原因；正常读取时为空。 */
    private String disconnectReason;

    /** 前端是否可以针对当前断开原因执行有限次数自动重连。 */
    private boolean reconnectAllowed;

}
