package com.jasonlat.ai.domain.agent.service.intent.classifier.node;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.service.intent.ContextTracker;
import com.jasonlat.ai.domain.agent.service.intent.classifier.AbstractIntentClassifierSupport;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author jasonlat
 * 2026-09-19  19:12
 */
@Slf4j
@Service("intentClassifierRootNode")
public class RootNode extends AbstractIntentClassifierSupport {

    @Resource
    private ContextTracker contextTracker;

    /**
     * 连续失败达到该次数后，后续分类跳过规则层、放宽 LLM 门槛
     */
    private static final int FAILURE_FALLBACK_THRESHOLD = 2;

    @Override
    protected IntentResultVO doApply(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws Exception {
        // 缓存键：会话 + 消息哈希，5 分钟内同一消息直接复用结果，避免重复分类开销
        String cacheKey = classifyCache.cacheKey(requestParameter.getChatSessionId(), requestParameter.getUserMessage());
        dynamicContext.setCacheKey(cacheKey);

        // 连续失败次数过多：跳过规则层，直接 LLM 兜底，门槛放宽
        int failures = contextTracker.getConsecutiveFailures(requestParameter.getChatSessionId());
        if (failures >= FAILURE_FALLBACK_THRESHOLD) {
            log.info("用户识别意图连续失败次数过多，跳过规则层, 路由：IntentClassifierRootNode --> LLMIntentClassifierNode");
            dynamicContext.setFailuresStatus(true);
            dynamicContext.setFinalResult(null);
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setFinalResult(classifyCache.get(cacheKey));
        return router(requestParameter, dynamicContext);
    }




    @Override
    public StrategyHandler<IntentRequestVO, DefaultClassifyFactory.DynamicContext, IntentResultVO> get(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws Exception {
        IntentResultVO finalResult = dynamicContext.getFinalResult();
        if (finalResult != null) {
            log.info("缓存命中，路由：intentClassifierRootNode --> endIntentClassifierNode");
            return getBean("endIntentClassifierNode");
        }

        log.info("缓存未命中，路由：intentClassifierRootNode --> ruleIntentClassifierNode");
        return getBean("ruleIntentClassifierNode");
    }
}
