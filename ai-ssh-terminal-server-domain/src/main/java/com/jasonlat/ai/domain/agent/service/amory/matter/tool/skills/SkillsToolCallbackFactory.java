package com.jasonlat.ai.domain.agent.service.amory.matter.tool.skills;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;

/**
 * 根据配置创建 Spring AI SkillsTool。
 */
public interface SkillsToolCallbackFactory {

    Optional<ToolCallback> create(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> configurations);
}