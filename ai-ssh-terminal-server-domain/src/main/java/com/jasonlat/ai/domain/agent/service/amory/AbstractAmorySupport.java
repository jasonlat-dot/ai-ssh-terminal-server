package com.jasonlat.ai.domain.agent.service.amory;

import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.types.utils.BeanUtils;
import com.jasonlat.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jakarta.annotation.Resource;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 抽象amory支持
 * @author jasonlat
 * 2026-03-31  20:33
 */

public abstract class AbstractAmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    protected final static String DEFAULT_AI_API_MAP_KEY = "default_ai_api_map_key";

    protected final static String DEFAULT_ChAT_MODEL_MAP_KEY = "default_chat_model_map_key";

    protected String getDefaultAiApiMapKey(String appName) {
        if (appName == null || appName.isEmpty()) {
            throw new RuntimeException("appName cannot be null");
        }
        return appName + "_" + DEFAULT_AI_API_MAP_KEY;
    }

    protected String getDefaultChatModelMapKey(String appName) {
        if (appName == null || appName.isEmpty()) {
            throw new RuntimeException("appName cannot be null");
        }
        return appName + "_" + DEFAULT_ChAT_MODEL_MAP_KEY;
    }

    @Resource
    protected BeanUtils beanUtils;

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
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
}
