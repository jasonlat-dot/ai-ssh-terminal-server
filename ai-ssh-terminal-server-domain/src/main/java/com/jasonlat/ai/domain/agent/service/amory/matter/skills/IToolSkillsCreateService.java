package com.jasonlat.ai.domain.agent.service.amory.matter.skills;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

public interface IToolSkillsCreateService {
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills);

}
