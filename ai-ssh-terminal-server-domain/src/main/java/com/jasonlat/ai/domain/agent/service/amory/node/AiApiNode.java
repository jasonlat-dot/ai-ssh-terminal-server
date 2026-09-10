package com.jasonlat.ai.domain.agent.service.amory.node;

import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * agent AiApi节点
 * @author jasonlat
 * 2026-03-31  20:59
 */
@Service
public class AiApiNode extends AbstractAmorySupport {

    private static final Logger log = LoggerFactory.getLogger(AiApiNode.class);
    @Resource
    private ChatModelNode chatModelNode;

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
        return chatModelNode;
    }

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
        log.info("Ai Agent 装配操作 - AiApiNode");
        // 编写api实例化的操作
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 获取默认的 openAiApi 配置
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();
        OpenAiApi openAiApi = buildOpenAiApi(aiApiConfig);
        // 设置默认的 openAiApi 到动态上下文
        dynamicContext.getOpenAiApiMap().put(getDefaultAiApiMapKey(aiAgentConfigTableVO.getAppName()), openAiApi);

        // 获取LlmAgents
        List<AiAgentConfigTableVO.Module.Agent> llmAgents = aiAgentConfigTableVO.getModule().getLlmAgents();
        if (llmAgents == null || llmAgents.isEmpty()) return this.router(requestParameter, dynamicContext);
        llmAgents.forEach(llmAgent -> {
            AiAgentConfigTableVO.Module.AiApi llmAgentAiApiConfig = llmAgent.getAiApi();
            if (null != llmAgentAiApiConfig) {
                OpenAiApi llmAgentOpenAiApi = buildOpenAiApi(llmAgentAiApiConfig);
                dynamicContext.getOpenAiApiMap().put(llmAgent.getName(), llmAgentOpenAiApi);
            }
        });

        // 路由到下一个节点，如不需要路由 可以直接返回结果
        return router(requestParameter, dynamicContext);
    }

    private OpenAiApi buildOpenAiApi(AiAgentConfigTableVO.Module.AiApi aiApiConfig ) {
        return OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(aiApiConfig.getCompletionsPath())
                .embeddingsPath(aiApiConfig.getEmbeddingsPath())
                .build();
    }
}
