package com.jasonlat.ai.domain.agent.service.amory.matter.skills.impl;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.skills.IToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jasonlat
 * 2026-04-05  12:27
 */
@Slf4j
@Service
public class DefaultIToolSkillsService implements IToolSkillsCreateService {
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) {
        String type = toolSkills.getType();
        String rootPath = toolSkills.getRootPath();

        List<ToolCallback> toolCallbacks = new ArrayList<>(4);
        if ("directory".equals(type)) {
            ToolCallback directoryToolCallback = SkillsTool.builder()
                    .addSkillsDirectory(rootPath).build();
            toolCallbacks.add(directoryToolCallback);
        }

        if ("resource".equals(type)) {
            ToolCallback resourceToolCallback = SkillsTool.builder()
                    .addSkillsResource(new ClassPathResource(rootPath)).build();
            toolCallbacks.add(resourceToolCallback);
        }
        return toolCallbacks.toArray(new ToolCallback[0]);
    }
}
