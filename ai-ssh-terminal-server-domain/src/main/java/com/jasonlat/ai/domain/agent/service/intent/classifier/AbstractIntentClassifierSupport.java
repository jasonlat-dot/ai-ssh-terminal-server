package com.jasonlat.ai.domain.agent.service.intent.classifier;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.service.intent.classifier.cache.ClassifyCache;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import com.jasonlat.design.framework.tree.AbstractMultiThreadStrategyRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author jasonlat
 * 2026-09-19  19:03
 */
@Slf4j
public abstract class AbstractIntentClassifierSupport extends AbstractMultiThreadStrategyRouter<IntentRequestVO, DefaultClassifyFactory.DynamicContext, IntentResultVO> {

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected ClassifyCache classifyCache;

    /**
     * 通用的 Bean 获取
     */
    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        T bean = (T) applicationContext.getBean(beanName);
        log.debug("ReAct链路-解析路由 Bean | beanName:{} | beanType:{}",
                beanName, bean.getClass().getSimpleName());
        return bean;
    }

    /**
     * 将分类结果降级为 UNKNOWN，用于连续失败后的兜底。
     * <p>
     * 保留原始结果的 entities 和 candidateIntents，方便主模型获取更多上下文。
     *
     * @param source 原始分类结果，可为 null
     * @return UNKNOWN 类型的 IntentResultVO
     */
    protected IntentResultVO toUnknown(IntentResultVO source) {
        return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .confidence(0.0)
                .entities(source != null ? source.getEntities() : Map.of())
                .candidateIntents(source != null ? source.getCandidateIntents() : List.of())
                .rawResponse(source != null ? source.getRawResponse() : null)
                .build();
    }


    /**
     * 多线程异步数据加载方法
     * <p>
     * 子类需要实现此方法来定义具体的异步数据加载逻辑。
     * 该方法在业务流程处理之前执行，用于预加载必要的数据。
     * </p>
     *
     * @param requestParameter 请求参数
     * @param dynamicContext   动态上下文
     * @throws ExecutionException   执行异常
     * @throws InterruptedException 中断异常
     * @throws TimeoutException     超时异常
     */
    @Override
    protected void multiThread(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
}
