package com.jasonlat.ai.domain.agent.model.valobj.dynamic;

import com.jasonlat.ai.domain.agent.service.multimodal.context.InvocationAttachmentScope;
import lombok.Builder;

/**
 * 单次 Agent 调用的运行上下文，不作为跨轮业务状态保留。
 *
 * 主 Agent 和子 Agent 分别持有自己的上下文对象；
 * 附件作用域、取消句柄由整个主请求共享。
 */
@Builder
public record AgentInvocationContext(
        /* 当前请求使用的 SSH 终端，可为空。 */
        String terminalSessionId,

        /* 最外层业务会话 ID，用于将子 Agent 事件发送到正确的前端对话。 */
        String rootSessionId,

        /* 当前 Runner 对应的运行时 Agent 名称。 */
        String runnerAgentName,

        /* 子 Agent 调用关联 ID；主 Agent 场景可为空。 */
        String agentCallId,

        /* 触发当前子 Agent 的工具调用 ID；主 Agent 场景可为空。 */
        String parentToolCallId,

        /* 整个请求共享的取消信号。 */
        AgentRunCancellation cancellation,

        /* 本轮经过校验的附件；资源由主请求统一释放。 */
        InvocationAttachmentScope attachmentScope
) {

    /** Session state 中只注册这一个入口。 */
    public static final String STATE_KEY = "agent-invocation-context";

    /**
     * 为子 Agent 创建独立上下文。
     * 继承终端、根会话、取消信号和附件，只替换子调用关联信息。
     */
    public AgentInvocationContext forChild(String childAgentName, String childAgentCallId, String dispatchToolCallId) {

        return new AgentInvocationContext(
                terminalSessionId,
                rootSessionId,
                childAgentName,
                childAgentCallId,
                dispatchToolCallId,
                cancellation,
                attachmentScope
        );
    }
}