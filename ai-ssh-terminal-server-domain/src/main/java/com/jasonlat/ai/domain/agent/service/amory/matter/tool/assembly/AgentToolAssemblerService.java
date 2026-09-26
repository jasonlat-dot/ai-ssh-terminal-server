package com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly;

import com.google.adk.tools.BaseTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj.AgentToolAssemblyContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用所有 AgentToolContributor，组合单个 Agent 的配置型工具。
 */
@Component
public class AgentToolAssemblerService {

    private final List<AgentToolContributor> contributors;

    public AgentToolAssemblerService(List<AgentToolContributor> contributors) {
        this.contributors = contributors;
    }

    public List<BaseTool> assemble(AgentToolAssemblyContext context) {

        Map<String, BaseTool> toolsByName = new LinkedHashMap<>();

        for (AgentToolContributor contributor : contributors) {
            List<? extends BaseTool> contributedTools = contributor.contribute(context);

            if (contributedTools == null) {
                continue;
            }

            for (BaseTool tool : contributedTools) {
                if (tool == null) {
                    continue;
                }

                BaseTool existing = toolsByName.putIfAbsent(tool.name(), tool);
                if (existing != null) {
                    throw new IllegalStateException(
                            "Agent 工具名称重复，agentName="
                                    + context.agentConfig().getName()
                                    + ", toolName="
                                    + tool.name()
                                    + ", existing="
                                    + existing.getClass().getName()
                                    + ", duplicate="
                                    + tool.getClass().getName());
                }
            }
        }

        return List.copyOf(toolsByName.values());
    }
}