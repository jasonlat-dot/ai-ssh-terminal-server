package com.jasonlat.ai.domain.ssh.model.valobj;

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
public class TerminalReadResult {

    /**
     * 本次返回状态。
     */
    private Status status;

    /**
     * 本次 SSH 输出。
     * 没有数据时返回空字符串。
     */
    private String data;

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


    /**
     * Long Poll 返回状态。
     */
    public enum Status {

        /**
         * 收到了 SSH 数据。
         */
        DATA,

        /**
         * Long Poll 超时，本次没有 SSH 数据。
         */
        TIMEOUT,

        /**
         * SSH 已经断开。
         */
        DISCONNECTED,

        /**
         * SSH Reader 发生异常。
         */
        READER_ERROR,

        /**
         * 同一个 session 又创建了新的 Long Poll，
         * 旧 Long Poll 被替换。
         * 正常前端逻辑一般不会出现。
         */
        REPLACED
    }


    /**
     * 创建有数据的响应。
     */
    public static TerminalReadResult data(String data, boolean connected, boolean bufferOverflow) {
        return TerminalReadResult.builder()
                .status(Status.DATA)
                .data(data == null ? "" : data)
                .hasData(data != null && !data.isEmpty())
                .connected(connected)
                .eof(false)
                .timeout(false)
                .bufferOverflow(bufferOverflow)
                .build();
    }


    /**
     * 创建 Long Poll 超时响应。
     */
    public static TerminalReadResult timeout(boolean connected) {
        return TerminalReadResult.builder()
                .status(Status.TIMEOUT)
                .data("")
                .hasData(false)
                .connected(connected)
                .eof(false)
                .timeout(true)
                .bufferOverflow(false)
                .build();
    }


    /**
     * 创建 SSH 断开响应。
     */
    public static TerminalReadResult disconnected(boolean eof) {
        return TerminalReadResult.builder()
                .status(Status.DISCONNECTED)
                .data("")
                .hasData(false)
                .connected(false)
                .eof(eof)
                .timeout(false)
                .bufferOverflow(false)
                .build();
    }


    /**
     * 创建 Reader 异常响应。
     */
    public static TerminalReadResult readerError() {
        return TerminalReadResult.builder()
                .status(Status.READER_ERROR)
                .data("")
                .hasData(false)
                .connected(false)
                .eof(false)
                .timeout(false)
                .bufferOverflow(false)
                .build();
    }

    /**
     * 创建 Reader 异常响应。
     */
    public static TerminalReadResult readerError(boolean connected) {
        return TerminalReadResult.builder()
                .status(Status.READER_ERROR)
                .data("")
                .hasData(false)
                .connected(connected)
                .eof(false)
                .timeout(false)
                .bufferOverflow(false)
                .build();
    }

    /**
     * 创建 Long Poll 被替换响应。
     */
    public static TerminalReadResult replaced(boolean connected) {

        return TerminalReadResult.builder()
                .status(Status.REPLACED)
                .data("")
                .hasData(false)
                .connected(connected)
                .eof(false)
                .timeout(false)
                .bufferOverflow(false)
                .build();
    }
}