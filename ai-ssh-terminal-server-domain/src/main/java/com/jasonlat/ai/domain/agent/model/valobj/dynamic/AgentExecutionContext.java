package com.jasonlat.ai.domain.agent.model.valobj.dynamic;

import com.jasonlat.ai.domain.agent.service.multimodal.context.InvocationAttachmentScope;
import lombok.Builder;
import lombok.Value;

/**
 * 子 Agent 执行上下文 - 贯穿一次动态派发全流程的上下文信息。
 * <p>
 * 由派发工具（DynamicPlanDispatchTool / BatchSubAgentDispatchTool）从
 * ToolContext 中提取构建，随任务传递给编排器与派发服务，
 * 用于在子 Agent 会话中复用父会话绑定的 SSH 终端会话。
 */
@Value
@Builder
public class AgentExecutionContext {

    /**
     * 发起对话的用户 ID
     */
    String userId;

    /**
     * 发起派发的父 Agent ID（即 ADK 中的 agentName）
     */
    String agentId;

    /**
     * 父会话 ID（invocationId），用于关联子 Agent 会话与父会话
     */
    String parentSessionId;

    /**
     * ADK 业务会话 ID，用于在父 invocation 事件尚未到达 SSE 时兜底关联事件。
     */
    String parentSessionKey;

    /** 父 Agent 本次批量/规划派发的工具调用 ID。 */
    String parentToolCallId;

    /**
     * 当前执行的任务 ID（预留）
     */
    String taskId;

    /** 发起派发的 Agent 调用上下文，供执行器派生子调用上下文。 */
    AgentInvocationContext invocationContext;

    /** 保留原有读取方法，编排器的取消处理无需一起重写。 */
    public AgentRunCancellation getCancellation() {
        return invocationContext == null ? null : invocationContext.cancellation();
    }

    public String getTerminalSessionId() {
        return invocationContext == null ? null : invocationContext.terminalSessionId();
    }

    public InvocationAttachmentScope getAttachmentScope() {
        return invocationContext == null ? null : invocationContext.attachmentScope();
    }
}
