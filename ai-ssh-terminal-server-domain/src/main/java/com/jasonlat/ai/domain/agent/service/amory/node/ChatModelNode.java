package com.jasonlat.ai.domain.agent.service.amory.node;

import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.mcp.client.IToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.mcp.client.factory.DefaultMcpClientFactory;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
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
    private ToolAssemblyNode toolAssemblyNode;

    @Resource
    private DefaultMcpClientFactory mcpClientFactory;

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
        return toolAssemblyNode;
    }

    private OpenAiChatModel buildChatModel(AiAgentConfigTableVO.Module.ChatModel chatModelConfig, OpenAiApi openAiApi ) {
        // 构建对话模型, 不在此处构建 skills 和 mcp 服务了
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(chatModelConfig.getModel())
                // 不要让 spring ai 内部调用工具 否则google adk 拿不到工具结果
                .internalToolExecutionEnabled(false)
                // 开启流式 usage 统计：OpenAI 协议要求 stream_options.include_usage=true，
                // 末块才返回完整 usage（含 prompt_tokens_details.cached_tokens 缓存命中）。
                .streamUsage(true);

        // 推理强度（仅推理模型生效，非推理模型忽略）
        String reasoningEffort = chatModelConfig.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }


}
