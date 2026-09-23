package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.orchestrator.DynamicAgentOrchestrator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.plan.PlanParser;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.plan.PlanValidator;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 批量子 Agent 派发工具 - 主 Agent 一次函数调用即可并发派发多个子 Agent 任务。
 * <p>
 * 与 {@link DynamicPlanDispatchTool}（先由独立规划器生成计划）不同，
 * 本工具由主 Agent 在函数调用参数中直接给出任务列表（含依赖关系），
 * 经校验后交给编排器按 DAG 并发执行，用于主 Agent 自主拆解的批量诊断场景。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时传入允许派发的 Agent 白名单。
 */
@Slf4j
public class BatchSubAgentDispatchTool extends BaseTool implements AdkToolProvider {

    /** 允许派发的子 Agent 名称白名单（所有已装配的 Agent） */
    private final List<String> allowedAgents;

    /** DAG 编排器，负责任务并发调度 */
    private final DynamicAgentOrchestrator orchestrator;

    /** 负责发布派发工具的调用/响应事件，供 SSE 可视化 */
    private final SubAgentDispatchEventPublisher eventPublisher;


    /** 计划解析/校验器：解析函数调用参数并校验依赖合法性 */
    private final PlanValidator planValidator = new PlanValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 兼容旧调用方式；不注入事件发布器时派发仍可正常执行，只是不产生嵌套事件。
     */
    public BatchSubAgentDispatchTool(List<String> allowedAgents, DynamicAgentOrchestrator orchestrator) {
        this(allowedAgents, orchestrator, null);
    }

    /**
     * 创建批量子 Agent 派发工具。
     *
     * @param allowedAgents       允许派发的子 Agent 名称白名单
     * @param orchestrator        任务 DAG 编排器
     * @param agentEventPublisher 可选事件发布器；为空时不回流工具事件
     */
    public BatchSubAgentDispatchTool(List<String> allowedAgents,
                                     DynamicAgentOrchestrator orchestrator,
                                     AgentEventPublisher agentEventPublisher) {
        super("dispatchSubAgents", "批量派发多个已配置子Agent，支持任务依赖、并行执行和结果汇总");
        this.allowedAgents = List.copyOf(allowedAgents);
        this.orchestrator = orchestrator;
        this.eventPublisher = new SubAgentDispatchEventPublisher(agentEventPublisher);
    }

    /**
     * 声明工具的函数签名：
     * tasks 为任务数组（每项含 agentName、request，可选 taskId/dependsOn/timeoutSeconds/maxRetries），
     * maxConcurrency 为可选并发上限，failFast 为可选的失败即中止开关。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        Schema taskSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(ImmutableMap.of(
                        "taskId", Schema.builder().type(Type.Known.STRING).build(),
                        "agentName", Schema.builder().type(Type.Known.STRING).build(),
                        "request", Schema.builder().type(Type.Known.STRING).build(),
                        "dependsOn", Schema.builder().type(Type.Known.ARRAY)
                                .items(Schema.builder().type(Type.Known.STRING).build()).build(),
                        "timeoutSeconds", Schema.builder().type(Type.Known.INTEGER).build(),
                        "maxRetries", Schema.builder().type(Type.Known.INTEGER).build()))
                .required(ImmutableList.of("agentName", "request"))
                .build();

        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(ImmutableMap.of(
                                "tasks", Schema.builder().type(Type.Known.ARRAY).items(taskSchema).build(),
                                "maxConcurrency", Schema.builder().type(Type.Known.INTEGER).build(),
                                "failFast", Schema.builder().type(Type.Known.BOOLEAN).build()))
                        .required(ImmutableList.of("tasks"))
                        .build())
                .build());
    }

    /**
     * 批量派发流程：解析参数 → 补全 taskId → 构建计划并校验（上限 10）→
     * 白名单校验 → 从 ToolContext 提取执行上下文（含父会话绑定的终端会话）→
     * 交给编排器按 DAG 执行 → 返回 {success, tasks, allSucceeded}。
     * 任何环节失败都返回 {success:false, error:...}，不向上抛异常。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        Object cancellationValue = toolContext.state().get(RUN_CANCELLATION);
        AgentRunCancellation cancellation = cancellationValue instanceof AgentRunCancellation value ? value : null;
        // 先发布工具调用，再执行计划，确保 UI 能展示“正在派发”状态。
        eventPublisher.publishCall(toolContext, name(), args);
        try {
            if (cancellation != null) cancellation.registerCurrentThread();
            // 解析函数调用参数中的任务列表
            List<Map<String, Object>> rawTasks = objectMapper.convertValue(args.get("tasks"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (rawTasks == null || rawTasks.isEmpty()) {
                Map<String, Object> result = ImmutableMap.of("success", false, "error", "tasks is empty");
                eventPublisher.publishResponse(toolContext, name(), result);
                return Single.just(result);
            }

            // 转为 DynamicTask 并补全缺失的 taskId
            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> objectMapper.convertValue(item, DynamicTask.class))
                    .peek(task -> {
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                    })
                    .toList();

            // 构建计划（maxConcurrency 默认 4）并校验结构/依赖合法性，任务数上限 10
            DynamicTaskPlan plan =
                    DynamicTaskPlan.builder()
                    .tasks(tasks)
                    .maxConcurrency(args.get("maxConcurrency") instanceof Number number ? number.intValue() : 4)
                    .failFast(Boolean.TRUE.equals(args.get("failFast")))
                    .build();
            planValidator.validate(plan, 10);

            // Agent 白名单校验：任务中不允许出现未装配的 Agent
            List<String> requestedAgents = tasks.stream().map(DynamicTask::getAgentName).toList();
            if (!new HashSet<>(allowedAgents).containsAll(requestedAgents)) {
                Map<String, Object> result = ImmutableMap.of("success", false, "error", "agent not allowed");
                eventPublisher.publishResponse(toolContext, name(), result);
                return Single.just(result);
            }

            // 构建执行上下文：透传父会话绑定的 SSH 终端会话，供子 Agent 内的 SSH 工具复用
            Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
            String terminalSessionId = terminalValue instanceof String value ? value : null;
            if (cancellation != null) cancellation.throwIfCancelled();

            AgentExecutionContext context = AgentExecutionContext.builder()
                    .terminalSessionId(terminalSessionId)
                    .userId(toolContext.userId())
                    .agentId(toolContext.agentName())
                    .parentSessionKey(toolContext.sessionId())
                    .parentSessionId(toolContext.invocationId())
                    .parentToolCallId(toolContext.functionCallId().orElse(null))
                    .cancellation(cancellation)
                    .build();

            // 到这开始执行任务计划
            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));

            eventPublisher.publishResponse(toolContext, name(), result);
            return Single.just(result);
        } catch (Exception exception) {
            log.error("批量子Agent派发失败", exception);
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
