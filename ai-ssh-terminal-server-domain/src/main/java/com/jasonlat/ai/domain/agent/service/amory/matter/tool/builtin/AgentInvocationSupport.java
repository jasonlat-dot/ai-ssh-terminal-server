package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin;

import com.google.adk.tools.ToolContext;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;

/**
 * 从 ADK 工具上下文读取本次调用信息。
 * 将类型校验集中在这里，避免每个工具重复强转。
 */
public final class AgentInvocationSupport {

    private AgentInvocationSupport() {
    }

    /** 允许没有调用上下文，适用于存在兜底逻辑的事件发布场景。 */
    public static AgentInvocationContext find(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Object value = toolContext.state().get(AgentInvocationContext.STATE_KEY);
        if (value == null) {
            return null;
        }

        if (!(value instanceof AgentInvocationContext invocation)) {
            throw new IllegalStateException("Agent 调用上下文类型不正确");
        }

        return invocation;
    }

    /** 正常工具执行必须经过 Runner 上下文装配；缺失时明确报错。 */
    public static AgentInvocationContext require(ToolContext toolContext) {
        AgentInvocationContext invocation = find(toolContext);
        if (invocation == null) {
            throw new IllegalStateException("Agent 调用上下文未初始化");
        }
        return invocation;
    }
}