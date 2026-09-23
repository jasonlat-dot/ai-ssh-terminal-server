package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.orchestrator.DynamicAgentOrchestrator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.plan.PlanParser;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.plan.PlanValidator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.plan.PlannerAgentBuilder;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态规划派发工具 - "先规划、后派发"的全自动多 Agent 编排入口。
 * <p>
 * 主 Agent 感到任务复杂时调用本工具（只传用户请求），流程：
 * PlannerAgentBuilder 让独立规划器 LLM 输出 JSON 任务计划 →
 * PlanParser 解析、PlanValidator 校验 → 编排器按 DAG 并发执行 →
 * 返回汇总结果。与 BatchSubAgentDispatchTool 的区别在于：
 * 计划由专门的规划器生成，主 Agent 无需自行拆解任务列表。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时注入规划器与编排器等依赖。
 */
@Slf4j
public class DynamicPlanDispatchTool extends BaseTool implements AdkToolProvider {

    private final PlannerAgentBuilder plannerAgentBuilder;
    private final DynamicAgentOrchestrator orchestrator;
    private final PlanParser planParser;
    private final PlanValidator planValidator;
    private final OpenAiApi openAiApi;
    private final String modelName;
    private final List<String> allowedAgents;

    /** 负责发布规划派发工具的调用/响应事件，供 SSE 可视化 */
    private final SubAgentDispatchEventPublisher eventPublisher;

    /**
     * 兼容旧调用方式；不注入事件发布器时派发仍可正常执行，只是不产生嵌套事件。
     */
    public DynamicPlanDispatchTool(PlannerAgentBuilder plannerAgentBuilder,
                                   DynamicAgentOrchestrator orchestrator,
                                   PlanParser planParser,
                                   PlanValidator planValidator,
                                   OpenAiApi openAiApi,
                                   String modelName,
                                   List<String> allowedAgents) {
        this(plannerAgentBuilder, orchestrator, planParser, planValidator, openAiApi,
                modelName, allowedAgents, null);
    }

    /**
     * 创建动态规划派发工具。
     *
     * @param plannerAgentBuilder 独立规划器的执行入口
     * @param orchestrator        任务 DAG 编排器
     * @param planParser          LLM 计划解析器
     * @param planValidator       计划依赖与结构校验器
     * @param openAiApi           规划器使用的模型 API
     * @param modelName           规划器模型名称
     * @param allowedAgents       允许派发的子 Agent 名称白名单
     * @param agentEventPublisher 可选事件发布器；为空时不回流工具事件
     */
    public DynamicPlanDispatchTool(PlannerAgentBuilder plannerAgentBuilder,
                                   DynamicAgentOrchestrator orchestrator,
                                   PlanParser planParser,
                                   PlanValidator planValidator,
                                   OpenAiApi openAiApi,
                                   String modelName,
                                   List<String> allowedAgents,
                                   AgentEventPublisher agentEventPublisher) {
        super("planAndDispatchSubAgents", "根据用户任务复杂度规划并派发多个子Agent");
        this.plannerAgentBuilder = plannerAgentBuilder;
        this.orchestrator = orchestrator;
        this.planParser = planParser;
        this.planValidator = planValidator;
        this.openAiApi = openAiApi;
        this.modelName = modelName;
        this.allowedAgents = List.copyOf(allowedAgents);
        this.eventPublisher = new SubAgentDispatchEventPublisher(agentEventPublisher);
    }

    /**
     * 声明工具的函数签名：入参仅一个必填字符串 request（用户原始任务描述）。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                                "request", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 规划 + 派发完整流程：规划器生成计划 JSON → 解析/校验 →
     * 构建执行上下文（透传终端会话）→ 编排器执行 → 返回 {success, tasks, allSucceeded}。
     * 任何环节失败都返回 {success:false, error:...}，不向上抛异常。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        Object cancellationValue = toolContext.state().get(RUN_CANCELLATION);
        AgentRunCancellation cancellation = cancellationValue instanceof AgentRunCancellation value ? value : null;
        String request = String.valueOf(args.getOrDefault("request", ""));
        // 先发布规划工具调用，再运行规划和 DAG 编排，前端可区分“规划中”和“执行中”。
        eventPublisher.publishCall(toolContext, name(), Map.of("request", request));
        try {
            if (cancellation != null) cancellation.registerCurrentThread();
            // 1. 由独立规划器 LLM 生成 JSON 任务计划
            String planJson = plannerAgentBuilder.plan(openAiApi, modelName, request, allowedAgents);
            if (cancellation != null) cancellation.throwIfCancelled();

            // 2. 解析（含白名单/非空校验）并做结构与依赖校验
            DynamicTaskPlan plan = planParser.parse(planJson, allowedAgents);

            planValidator.validate(plan, 10);

            Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
            String terminalSessionId = terminalValue instanceof String value ? value : null;
            if (cancellation != null) cancellation.throwIfCancelled();

            // 3. 构建执行上下文：透传父会话绑定的 SSH 终端会话
            // toolContext 可能为 null（如 LLM 直接触发工具调用而未经过完整 Runner 会话），需兜底
            AgentExecutionContext context = AgentExecutionContext.builder()
                    .userId(toolContext.userId())
                    .agentId(toolContext.agentName())
                    .parentSessionId(toolContext.invocationId())
                    .parentToolCallId(toolContext.functionCallId().orElse(null))
                    .parentSessionKey(toolContext.sessionId())
                    .terminalSessionId(terminalSessionId)
                    .cancellation(cancellation)
                    .build();

            // 4. 交给编排器按 DAG 并发执行
            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));
            eventPublisher.publishResponse(toolContext, name(), result);
            return Single.just(result);
        } catch (Exception exception) {
            log.error("动态规划派发失败", exception);
            Map<String, Object> result = ImmutableMap.of(
                    "success", false,
                    "error", String.valueOf(exception.getMessage()));
            eventPublisher.publishResponse(toolContext, name(), result);
            return Single.just(result);
        } finally {
            if (cancellation != null) cancellation.unregisterCurrentThread();
        }
    }

    @Override
    public List<? extends BaseTool> getTools() {
        return List.of(this);
    }
}
