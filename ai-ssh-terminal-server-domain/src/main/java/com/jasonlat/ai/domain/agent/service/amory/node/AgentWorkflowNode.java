package com.jasonlat.ai.domain.agent.service.amory.node;

import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import com.jasonlat.ai.domain.agent.service.amory.AbstractAmorySupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.node.workflow.LoopAgentNode;
import com.jasonlat.ai.domain.agent.service.amory.node.workflow.ParallelAgentNode;
import com.jasonlat.ai.domain.agent.service.amory.node.workflow.SequentialAgentNode;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author jasonlat
 * 2026-04-01  19:59
 */
@Service
public class AgentWorkflowNode extends AbstractAmorySupport {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowNode.class);

    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private RunnerNode runnerNode;

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
        log.info("Ai Agent 配置操作 - AgentWorkflowNode");
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // 清空当前流程节点
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }
        // 设置当前处理的流程接节点
        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));
        // 步骤值增加
        dynamicContext.addCurrentStepIndex();

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
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        if (null == currentAgentWorkflow) {
            return runnerNode;
        }
        String agentType = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(agentType);
        String node = agentTypeEnum.getNode();
        return switch (node) {
            case "sequentialAgentNode" -> sequentialAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "loopAgentNode" -> loopAgentNode;
            default -> {
                log.error("Ai Agent 配置操作 - AgentWorkflowNode - 找不到对应的节点, 直接路由到 runnerNode");
                yield runnerNode;
            }
        };
    }
}
