package com.jasonlat.ai.domain.agent.service.amory.matter.tool.skills.impl;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.skills.SkillsToolCallbackFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * SkillsTool 默认创建工厂。
 *
 * 所有目录统一添加到同一个 SkillsTool 中，避免多个 SkillsTool
 * 都使用名称“Skill”而产生工具名称冲突。
 */
@Service
public class DefaultSkillsToolCallbackFactory implements SkillsToolCallbackFactory {

    @Override
    public Optional<ToolCallback> create(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> configurations) {

        if (configurations == null || configurations.isEmpty()) {
            return Optional.empty();
        }

        SkillsTool.Builder builder = SkillsTool.builder();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills config : configurations) {

            if (config == null) {
                continue;
            }

            String rootPath = config.getRootPath();
            if (!StringUtils.hasText(rootPath)) {
                throw new IllegalArgumentException("Skills root-path 不能为空");
            }

            String type = StringUtils.hasText(config.getType()) ? config.getType().trim() : "resource";

            switch (type) {
                case "resource" -> builder.addSkillsResource(new ClassPathResource(rootPath));
                case "directory" -> builder.addSkillsDirectory(rootPath);
                default -> throw new IllegalArgumentException("不支持的 Skills 类型: " + type);
            }
        }

        return Optional.of(builder.build());
    }
}