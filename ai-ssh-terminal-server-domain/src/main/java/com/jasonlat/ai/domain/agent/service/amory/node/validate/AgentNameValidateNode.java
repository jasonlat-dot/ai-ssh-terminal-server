package com.jasonlat.ai.domain.agent.service.amory.node.validate;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.AbstractValidateSupport;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultValidateFactory;
import com.jasonlat.ai.types.exception.AppException;
import com.jasonlat.design.framework.tree.StrategyHandler;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class AgentNameValidateNode extends AbstractValidateSupport {


    /** 智能体名称分隔符 */
    private static final String NAME_SEPARATOR = "_";

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
    protected Boolean doApply(List<AiAgentConfigTableVO> requestParameter, DefaultValidateFactory.DynamicContext dynamicContext) throws Exception {
        // LlmAgents name cannot be duplicated
        requestParameter.forEach(aiAgentConfigTableVO -> {
            // llmAgents
            List<AiAgentConfigTableVO.Module.Agent> llmAgents = aiAgentConfigTableVO.getModule().getLlmAgents();
            List<String> agentNames = new ArrayList<>(8);
            if (llmAgents != null && !llmAgents.isEmpty()) {
                for (AiAgentConfigTableVO.Module.Agent llmAgent : llmAgents) {
                    if (agentNames.contains(llmAgent.getName())) {
                        throw new AppException("[agent-workflows.name and llm-agents.name] union have the same agentName【" + llmAgent.getName() + "】, cannot be duplicated");
                    }
                    agentNames.add(llmAgent.getName());
                    // 拼接格式: appName_name
                    llmAgent.setName(aiAgentConfigTableVO.getAppName() + NAME_SEPARATOR + llmAgent.getName());
                }
            }
            // 处理workflow-agents
            List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();
            if (agentWorkflows != null && !agentWorkflows.isEmpty()) {
                for (AiAgentConfigTableVO.Module.AgentWorkflow agentWorkflow : agentWorkflows) {
                    if (agentWorkflow.getSubAgents() == null || agentWorkflow.getSubAgents().isEmpty()) {
                        throw new AppException("agent-workflows.subAgents cannot be empty");
                    }
                    if (agentNames.contains(agentWorkflow.getName())) {
                        // agent-workflows.name with llm-agents.name 的并集有重复名字
                        throw new AppException("[agent-workflows.name and llm-agents.name] union have the same agentName【" + agentWorkflow.getName() + "】, cannot be duplicated");
                    }
                    agentNames.add(agentWorkflow.getName());

                    // 拼接格式: appName_name
                    agentWorkflow.setName(aiAgentConfigTableVO.getAppName() + NAME_SEPARATOR + agentWorkflow.getName());
                    // 处理 agent-workflows 的 subAgents
                    List<String> prefixedSubAgents = getPrefixedSubAgents(aiAgentConfigTableVO.getAppName(), agentWorkflow);
                    agentWorkflow.setSubAgents(prefixedSubAgents);
                }
            }
            // 处理 runner
            AiAgentConfigTableVO.Module.Runner runner = aiAgentConfigTableVO.getModule().getRunner();
            if (runner == null || StringUtils.isBlank(runner.getAgentName())) {
                throw new AppException("runner.agentName cannot be empty");
            }
            // 拼接格式: appName_name
            runner.setAgentName(aiAgentConfigTableVO.getAppName() + NAME_SEPARATOR + runner.getAgentName());
        });
        return true;
    }

    private static @NotNull List<String> getPrefixedSubAgents(String appName, AiAgentConfigTableVO.Module.AgentWorkflow agentWorkflow) {
        List<String> subAgents = agentWorkflow.getSubAgents();
        if (subAgents == null || subAgents.isEmpty()) {
            throw new AppException("agent-workflows.subAgents cannot be empty");
        }
        List<String> prefixedSubAgents = new ArrayList<>(subAgents.size());
        subAgents.forEach(subAgent -> {
            // 拼接格式: appName_name
            subAgent = appName + NAME_SEPARATOR + subAgent;
            prefixedSubAgents.add(subAgent);
        });
        return prefixedSubAgents;
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
    public StrategyHandler<List<AiAgentConfigTableVO>, DefaultValidateFactory.DynamicContext, Boolean> get(List<AiAgentConfigTableVO> requestParameter, DefaultValidateFactory.DynamicContext dynamicContext) throws Exception {
        return super.get(requestParameter, dynamicContext);
    }
}
