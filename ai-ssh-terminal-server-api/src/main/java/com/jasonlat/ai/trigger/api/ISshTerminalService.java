package com.jasonlat.ai.trigger.api;


import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;

/**
 * SSH 终端服务接口
 * 提供终端会话的打开、读写、调整大小、关闭等操作
 */
public interface ISshTerminalService {

    /**
     * 打开终端会话
     */
    Response<TerminalOpenResponseDTO> openTerminal(TerminalOpenRequestDTO requestDTO);

    /**
     * 执行命令并获取输出
     */
    Response<TerminalExecResponseDTO> execCommand(TerminalExecRequestDTO requestDTO);

    /**
     * 向终端写入原始输入（按键、粘贴等）
     */
    Response<Void> writeToTerminal(TerminalWriteRequestDTO requestDTO);

    /**
     * 从终端读取输出数据
     */
    Response<TerminalReadResponseDTO> readFromTerminal(String sessionId);

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
