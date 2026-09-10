package com.jasonlat.ai.domain.agent.service.amory.node;

import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.IToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.skills.IToolSkillsCreateService;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jasonlat
 * 2026-04-01  18:45
 */
@Service
public class ChatModelNode extends AbstractAmorySupport {

    private static final Logger log = LoggerFactory.getLogger(ChatModelNode.class);

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory mcpClientFactory;

    @Resource
    private IToolSkillsCreateService skillsCreateService;

    /**
     * 业务流程处理方法
     * <p>
     * 子类需要实现此方法来定义具体的业务处理逻辑。
     * 该方法在异步数据加载完成后执行。
     * </p>
     *
     * @param requestParameter 请求参数
     * @param dynamicContext   动态上下文
     * @return 处理结果
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        // 获取默认配置的 openAiApi
        OpenAiApi defaultOpenAiApi = dynamicContext.getOpenAiApiMap().get(getDefaultAiApiMapKey(aiAgentConfigTableVO.getAppName()));
        // 构建默认的 chatModel, 放入上下文。
        OpenAiChatModel defaultChatModel = buildChatModel(chatModelConfig, defaultOpenAiApi);
        dynamicContext.getChatModelMap().put(getDefaultChatModelMapKey(aiAgentConfigTableVO.getAppName()), defaultChatModel);

        // 获取LlmAgents
        List<AiAgentConfigTableVO.Module.Agent> llmAgents = aiAgentConfigTableVO.getModule().getLlmAgents();
        if (llmAgents == null || llmAgents.isEmpty()) return this.router(requestParameter, dynamicContext);
        // 循环处理
        llmAgents.forEach(llmAgent -> {
            AiAgentConfigTableVO.Module.ChatModel llmAgentChatModelConfig = llmAgent.getChatModel();
            // 如果没有自定义配置的 chatModel， 则使用默认的 chatModel(包括mcp、skills) 不做处理
            // 只处理自定义配置的 chatModel
            if (null != llmAgentChatModelConfig) {
                // 获取自定义配置的 openAiApi
                OpenAiApi llmAgentOpenAiApi = dynamicContext.getOpenAiApiMap().get(llmAgent.getName());
                if (null == llmAgentOpenAiApi ) {
                    // 如果没有自定义配置的 openAi， 则使用默认的 openAiApi
                    llmAgentOpenAiApi = defaultOpenAiApi;
                }
                OpenAiChatModel llmAgentChatModel = buildChatModel(llmAgentChatModelConfig, llmAgentOpenAiApi);
                dynamicContext.getChatModelMap().put(llmAgent.getName(), llmAgentChatModel);
            }
        });

        // 路由下一个节点
        return router(requestParameter, dynamicContext);
    }

    /**
     * 获取待执行的策略处理器
     * <p>
     * 根据请求参数和动态上下文的内容，选择并返回合适的策略处理器。
     * 实现类需要根据具体的业务规则来实现策略选择逻辑。
     * </p>
     *
     * @param requestParameter 请求参数，用于确定策略选择的依据
     * @param dynamicContext   动态上下文，包含策略选择过程中需要的额外信息
     * @return 选择的策略处理器，如果没有找到合适的策略则返回null
     * @throws Exception 策略选择过程中可能抛出的异常
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

    private OpenAiChatModel buildChatModel(AiAgentConfigTableVO.Module.ChatModel chatModelConfig, OpenAiApi openAiApi ) {
        List<ToolCallback> toolCallbacks = new ArrayList<>(8);
        // 获取默认的 mcpSyncClient
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        if ((toolMcpList != null && !toolMcpList.isEmpty())) {
            toolMcpList.forEach(toolMcp -> {
                try {
                    IToolMcpCreateService toolMcpCreateService = mcpClientFactory.getToolMcpCreateService(toolMcp);
                    ToolCallback[] mcpToolCallbacks = toolMcpCreateService.buildToolCallback(toolMcp);
                    toolCallbacks.addAll(List.of(mcpToolCallbacks));
                } catch (Exception e) {
                    log.error("创建 mcpSyncClient 失败", e);
                    throw new RuntimeException(e);
                }
            });
        }
        // 获取默认的 skills
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();
        if ((toolSkillsList != null && !toolSkillsList.isEmpty())) {
            toolSkillsList.forEach(toolSkills -> {
                try {
                    ToolCallback[] toolSkillsCallbacks = skillsCreateService.buildToolCallback(toolSkills);
                    toolCallbacks.addAll(List.of(toolSkillsCallbacks));
                } catch (Exception e) {
                    log.error("创建 skills 失败", e);
                    throw new RuntimeException(e);
                }
            });
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelConfig.getModel())
                        .toolCallbacks(toolCallbacks)
                        .build())
                .build();
    }

}
