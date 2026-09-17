package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ReAct 执行结果 DTO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReActResultDTO {

    /**
     * 最终响应内容
     */
    private String content;

    /**
     * 总执行步数
     */
    private int totalSteps;

    /**
     * 总工具调用次数
     */
    private int totalToolCalls;

    /**
     * 是否达到最大步数终止
     */
    private boolean maxStepsReached;

    /**
     * 是否用户手动停止
     */
    private boolean userStopped;

    /**
     * 是否 idle 超时终止
     */
    private boolean idleTimeout;

    /**
     * 终止原因：completed / finish / max_steps / max_tool_calls / user_stop / idle_timeout / error
     */
    private String stopReason;

    /**
     * 工具调用列表
     */
    private List<ToolCallDTO> toolCalls;

    /**
     * 工具执行结果列表
     */
    private List<ToolResultDTO> toolResults;

    /**
     * 错误信息（如有）
     */
    private String error;

}
