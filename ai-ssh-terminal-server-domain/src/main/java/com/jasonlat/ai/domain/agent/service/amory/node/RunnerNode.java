package com.jasonlat.ai.domain.agent.service.amory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.types.exception.AppException;
import com.jasonlat.design.framework.tree.StrategyHandler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jasonlat
 * 2026-04-01  21:25
 */
@Service
public class RunnerNode extends AbstractAmorySupport {
    private static final Logger log = LoggerFactory.getLogger(RunnerNode.class);

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
        log.info("Ai Agent 配置操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();

        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.AgentDefinition agentDefinition = aiAgentConfigTableVO.getAgentDefinition();
        String agentId = agentDefinition.getAgentId();
        String agentDesc = agentDefinition.getAgentDesc();
        String agentName = agentDefinition.getAgentName();

        InMemoryRunner inMemoryRunner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentName(agentName)
                .agentId(agentId)
                .agentDesc(agentDesc)
                .sessionExpireSeconds(aiAgentConfigTableVO.getSessionExpireSeconds())
                .runner(inMemoryRunner)
                .build();

        // 注册到Spring容器
        beanUtils.registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);
        return aiAgentRegisterVO;
    }

    private @NotNull InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(runnerAgentName);
        if (baseAgent == null) {
            // runner.agentName 不正确
            throw new AppException("runner.agentName is illegal");
        }
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        // 创建 runner
        if (null == pluginNameList || pluginNameList.isEmpty()) return new InMemoryRunner(baseAgent, appName);

        List<BasePlugin> basePlugins = new ArrayList<>(pluginNameList.size());
        pluginNameList.forEach(pluginName -> {
            BasePlugin basePlugin = beanUtils.getBean(pluginName, BasePlugin.class);
            basePlugins.add(basePlugin);
        });
        return new InMemoryRunner(baseAgent, appName, basePlugins);
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
        return super.get(requestParameter, dynamicContext);
    }
}
