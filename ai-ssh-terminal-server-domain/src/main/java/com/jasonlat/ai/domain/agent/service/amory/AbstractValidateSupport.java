package com.jasonlat.ai.domain.agent.service.amory;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultValidateFactory;
import com.jasonlat.design.framework.tree.AbstractMultiThreadStrategyRouter;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author jasonlat
 * 2026-04-04  12:30
 */
public abstract class AbstractValidateSupport extends AbstractMultiThreadStrategyRouter<List<AiAgentConfigTableVO>, DefaultValidateFactory.DynamicContext,Boolean > {
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
    protected void multiThread(List<AiAgentConfigTableVO> requestParameter, DefaultValidateFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
}
