package com.jasonlat.ai.trigger.api;


import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;
import java.util.concurrent.CompletableFuture;

/**
 * SSH 终端服务接口
 * 提供终端会话的打开、读写、调整大小、关闭等操作
 */
public interface ISshTerminalService {

    /**
     * 建立 SSH 连接并创建可取消的终端会话。
     */
    Response<TerminalOpenResponseDTO> connectTerminal(TerminalOpenRequestDTO requestDTO);

    /**
     * 取消指定请求的终端建连。
     */
    Response<Void> cancelConnect(String requestId, String connectionId);

    /**
     * 向终端写入原始输入（按键、粘贴等）
     */
    Response<Void> writeToTerminal(TerminalWriteRequestDTO requestDTO);

    /**
     * 通过长轮询读取终端输出及连接状态。
     */
    CompletableFuture<Response<TerminalReadResultDTO>> readAsyncFromTerminal(String sessionId);

    /**
     * 查询某个页签自己的终端是否仍然连接。
     */
    Response<TerminalConnectionStateDTO> isTerminalConnected(String sessionId);

    /**
     * 调整终端窗口大小
     */
    Response<Void> resizeTerminal(TerminalResizeRequestDTO requestDTO);

    /**
     * 关闭终端会话
     */
    Response<Void> closeTerminal(String sessionId);

}
