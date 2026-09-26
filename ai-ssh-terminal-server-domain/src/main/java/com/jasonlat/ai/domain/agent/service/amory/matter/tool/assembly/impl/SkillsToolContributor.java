package com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.impl;

import com.google.adk.tools.BaseTool;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.skills.SkillsToolCallbackFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.adapter.springai.SpringToolCallbackAdkAdapterFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.AgentToolContributor;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj.AgentToolAssemblyContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 将 ChatModel 中的 Skills 配置贡献为 ADK 工具。
 */
@Component
public class SkillsToolContributor implements AgentToolContributor {

    private final SkillsToolCallbackFactory skillsFactory;
    private final SpringToolCallbackAdkAdapterFactory adapterFactory;

    public SkillsToolContributor(SkillsToolCallbackFactory skillsFactory, SpringToolCallbackAdkAdapterFactory adapterFactory) {
        this.skillsFactory = skillsFactory;
        this.adapterFactory = adapterFactory;
    }

    @Override
    public List<? extends BaseTool> contribute(AgentToolAssemblyContext context) {
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = context.chatModelConfig();
        if (chatModelConfig == null) {
            return List.of();
        }

        Optional<ToolCallback> skillsCallback = skillsFactory.create(chatModelConfig.getToolSkillsList());

        if (skillsCallback.isEmpty()) {
            return List.of();
        }

        BaseTool adkTool = adapterFactory.adapt(skillsCallback.get());

        return List.of(adkTool);
    }
}