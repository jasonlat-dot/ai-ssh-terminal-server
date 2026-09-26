package com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly;

import com.google.adk.tools.BaseTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj.AgentToolAssemblyContext;

import java.util.List;

/**
 * 配置型 ADK 工具贡献者。
 * 新增其他工具类型时，只需增加新的实现类，
 * AgentToolAssembler 和 AgentNode 不需要修改。
 */
public interface AgentToolContributor {

    List<? extends BaseTool> contribute(AgentToolAssemblyContext context);
}