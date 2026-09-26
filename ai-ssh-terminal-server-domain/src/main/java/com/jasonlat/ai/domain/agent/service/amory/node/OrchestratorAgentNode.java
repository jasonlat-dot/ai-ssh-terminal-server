package com.jasonlat.ai.domain.agent.service.amory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.patch.LocalSpringAI;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.factory.CustomRunnerFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.BatchSubAgentDispatchTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.DynamicPlanDispatchTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.SubAgentDispatchTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.excution.DynamicAgentOrchestrator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.plan.PlanParser;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.plan.PlanValidator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.plan.PlannerAgentBuilder;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 负责统筹调度其他子 Agent 的主节点
 * @author jasonlat
 * 2026-09-22  21:54
 */
@Service
public class OrchestratorAgentNode extends AbstractAmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private CustomRunnerFactory customRunnerFactory;
    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;
    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;
    @Resource
    private PlanParser planParser;
    @Resource
    private PlanValidator planValidator;

    /**
     * 发布器用于把子 Agent Runner 和 SSH 工具产生的事件转发到父请求 SSE 流。
     */
    @Resource
    private AgentEventPublisher agentEventPublisher;

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgentNode.class);

    /** 智能体名称分隔符 */
    private static final String NAME_SEPARATOR = "_";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        buildAgentTools(aiAgentConfigTableVO, dynamicContext);

        return router(requestParameter, dynamicContext);
    }


    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

    /**
     * 为配置了 subAgents 的父 Agent 重新装配"多 Agent 派发"能力：
     * <ol>
     *   <li>把每个声明的子 Agent 包装成 {@link SubAgentDispatchTool}（单 Agent 派发工具）</li>
     *   追加 {@link BatchSubAgentDispatchTool}（主 Agent 自行拆解任务的批量派发工具）</li>
     *   <li>追加 {@link DynamicPlanDispatchTool}（由独立规划器生成计划的动态派发工具）</li>
     * </ol>
     * 用同一套配置重建父 Agent 并覆盖 agentGroup，使其获得派发类工具；
     * 子 Agent 未在配置中声明时直接抛出异常，fail-fast。
     */
    private void buildAgentTools(AiAgentConfigTableVO aiAgentConfigTableVO, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getLlmAgents();
        Map<String, BaseAgent> agentGroup = dynamicContext.getAgentGroup();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            // 未声明 subAgents 的 Agent 不需要派发能力，跳过重建
            List<String> subAgentNames = agentConfig.getSubAgents();
            if (subAgentNames == null || subAgentNames.isEmpty()) {
                continue;
            }

            /*
             * 父 Agent 重建时也要保留配置型共享工具，例如 Skill。
             * 这里只添加配置型工具，不添加 executeCommand，
             * 因此父 Agent 仍然只能通过子 Agent 操作 SSH。
             */
            List<Object> adkTools = new ArrayList<>(dynamicContext.getConfiguredAdkToolMap().getOrDefault(agentConfig.getName(), List.of()));

            // 为每个声明的子 Agent 构建单独的派发工具（工具名即子 Agent 名，LLM 可直接点名调用）
            for (String subAgentName : subAgentNames) {
                // AgentNameValidateNode#doApply 将名称设置为： appName_agentName 此处统一
                String realSubAgentName = aiAgentConfigTableVO.getAppName() + NAME_SEPARATOR + subAgentName;
                BaseAgent subAgent = agentGroup.get(realSubAgentName);
                if (subAgent == null) {
                    throw new IllegalArgumentException("sub agent not found: " + subAgentName);
                }
                adkTools.add(new SubAgentDispatchTool(subAgent, customRunnerFactory, agentEventPublisher));
            }

            List<String> allowedSubAgentNames = subAgentNames.stream()
                    .map(subAgentName ->
                            aiAgentConfigTableVO.getAppName()
                                    + NAME_SEPARATOR
                                    + subAgentName)
                    .toList();

            // 批量派发工具：主 Agent 自行拆解任务列表并发派发，agentEventPublisher 推送。
            adkTools.add(new BatchSubAgentDispatchTool(
                    aiAgentConfigTableVO.getAppName(),
                    allowedSubAgentNames,
                    dynamicAgentOrchestrator,
                    agentEventPublisher));

            ChatModel chatModel = dynamicContext.getChatModelMap().get(agentConfig.getName());
            if (chatModel == null) {
                chatModel = dynamicContext.getChatModelMap().get(getDefaultChatModelMapKey(aiAgentConfigTableVO.getAppName()));
            }

            String modelName = aiAgentConfigTableVO.getModule().getChatModel().getModel();
            AiAgentConfigTableVO.Module.ChatModel llmChatModel = agentConfig.getChatModel();
            if (llmChatModel != null) {
                modelName = llmChatModel.getModelOrDefault(modelName);
            }

            OpenAiApi openAiApi = dynamicContext.getOpenAiApiMap().get(agentConfig.getName());

            if (openAiApi == null) {
                openAiApi = dynamicContext.getOpenAiApiMap().get(getDefaultAiApiMapKey(aiAgentConfigTableVO.getAppName()));
            }

            if (openAiApi == null) {
                throw new IllegalStateException("未找到 " + agentConfig.getName() + " Agent 的模型 OpenAiApi 配置。" );
            }

            // 动态规划派发工具：由独立规划器 LLM 生成任务计划后派发
            adkTools.add(new DynamicPlanDispatchTool(
                    plannerAgentBuilder,
                    dynamicAgentOrchestrator,
                    planParser,
                    planValidator,
                    openAiApi,
                    modelName,
                    agents.stream().map(AiAgentConfigTableVO.Module.Agent::getName).toList(),
                    agentEventPublisher));

            // 和前面node节点里一样，创建智能体
            LlmAgent parentAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new LocalSpringAI(chatModel))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(adkTools)
                    .build();

            agentGroup.put(agentConfig.getName(), parentAgent);
        }
    }
}
