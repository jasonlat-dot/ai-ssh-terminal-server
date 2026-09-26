package com.jasonlat.ai.domain.agent.service.amory.node;

import com.google.adk.tools.BaseTool;
import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.AgentToolAssemblerService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj.AgentToolAssemblyContext;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 根据每个 Agent 的最终 ChatModel 配置装配 ADK 工具。
 */
@Slf4j
@Service
public class ToolAssemblyNode extends AbstractAmorySupport {

    @Resource
    private AgentToolAssemblerService agentToolAssemblerService;

    @Resource
    private AgentNode agentNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ToolAssemblyNode");

        AiAgentConfigTableVO config = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module module = config.getModule();
        List<AiAgentConfigTableVO.Module.Agent> agents = module.getLlmAgents();

        if (agents == null || agents.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        for (AiAgentConfigTableVO.Module.Agent agent : agents) {
            /*
             * Agent 有独立 chat-model 时使用独立配置；
             * 否则继承 module.chat-model。
             */
            AiAgentConfigTableVO.Module.ChatModel effectiveChatModel =
                    agent.getChatModel() != null ? agent.getChatModel() : module.getChatModel();

            AgentToolAssemblyContext assemblyContext =
                    new AgentToolAssemblyContext(config.getAppName(), agent, effectiveChatModel);

            List<BaseTool> tools = agentToolAssemblerService.assemble(assemblyContext);

            dynamicContext.getConfiguredAdkToolMap().put(agent.getName(), tools);

            log.info("Agent 配置型工具装配完成 agentName={}, tools={}",
                    agent.getName(),
                    tools.stream()
                            .map(BaseTool::name)
                            .toList());
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter,DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }
}