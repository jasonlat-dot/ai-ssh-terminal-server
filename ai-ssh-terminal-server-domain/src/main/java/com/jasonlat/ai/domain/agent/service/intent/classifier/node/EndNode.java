package com.jasonlat.ai.domain.agent.service.intent.classifier.node;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.service.intent.ContextTracker;
import com.jasonlat.ai.domain.agent.service.intent.classifier.AbstractIntentClassifierSupport;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author jasonlat
 * 2026-09-19  20:18
 */
@Slf4j
@Component("endIntentClassifierNode")
public class EndNode extends AbstractIntentClassifierSupport {

    @Resource
    private ContextTracker contextTracker;

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
    protected IntentResultVO doApply(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws Exception {
        IntentResultVO finalResult = dynamicContext.getFinalResult();
        recordAndCache(dynamicContext, requestParameter.getChatSessionId(), finalResult);
        log.info("最终用户识别意图结果：意图类型：{} | 置信度:{} | ", finalResult.getIntent(), finalResult.getConfidence());
        return finalResult;
    }

    /**
     * 将分类结果写入上下文追踪器，并放入 LRU 缓存。
     * <p>
     * 缓存键为 {@code sessionId:hash(message)}，有效期 5 分钟，
     * 超过 200 条时自动淘汰最久未使用的条目。
     *
     * @param result    分类结果
     */
    private void recordAndCache(DefaultClassifyFactory.DynamicContext dynamicContext, String sessionId, IntentResultVO result) {
        contextTracker.updateContext(sessionId, result);
        // 缓存 5 分钟
        classifyCache.putResult(dynamicContext.getCacheKey(), result);
    }
}
