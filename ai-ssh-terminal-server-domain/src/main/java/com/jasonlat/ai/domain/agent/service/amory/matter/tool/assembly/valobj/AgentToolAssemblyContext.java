package com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

/**
 * 单个 Agent 的工具装配上下文。 assembly组件
 */
public record AgentToolAssemblyContext(
        String appName,
        AiAgentConfigTableVO.Module.Agent agentConfig,
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
}