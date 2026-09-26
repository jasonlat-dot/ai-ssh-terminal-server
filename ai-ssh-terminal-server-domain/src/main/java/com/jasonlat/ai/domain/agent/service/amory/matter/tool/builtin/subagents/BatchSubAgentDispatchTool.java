package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents;

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
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.register.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.excution.DynamicAgentOrchestrator;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.plan.PlanValidator;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

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

    private final String appName;


    /** 计划解析/校验器：解析函数调用参数并校验依赖合法性 */
    private final PlanValidator planValidator = new PlanValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建批量子 Agent 派发工具。
     *
     * @param allowedAgents       允许派发的子 Agent 名称白名单
     * @param orchestrator        任务 DAG 编排器
     * @param agentEventPublisher 可选事件发布器；为空时不回流工具事件
     */
    public BatchSubAgentDispatchTool(String appName, List<String> allowedAgents,
                                     DynamicAgentOrchestrator orchestrator,
                                     AgentEventPublisher agentEventPublisher) {
        super("dispatchSubAgents", "批量派发多个已配置子Agent，支持任务依赖、并行执行和结果汇总");
        this.appName = appName;
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
        /*
         * 保持名称顺序稳定，避免 HashSet 每次生成的描述顺序不同，
         * 影响模型理解以及工具 Schema 缓存。
         */
        String allowedAgentNames = allowedAgents.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        Schema taskSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .description("""
                        一个待派发的子 Agent 任务。
                        字段规则：
                        1. agentName 和 request 为必填字段。
                        2. taskId 是当前批次内的任务唯一标识；省略时由后端自动生成。
                        3. dependsOn 只能引用当前 tasks 数组中其他任务的 taskId。
                        4. 没有前置依赖时，dependsOn 可以省略或传空数组。
                        5. timeoutSeconds 和 maxRetries 可以省略，由后端使用默认值。
                        6. 必须严格使用声明的字段名称，不要使用 id、agent、task、prompt、
                           dependencies 等别名，也不要添加未声明字段。
                        """)
                .properties(ImmutableMap.of(
                        "taskId",
                        Schema.builder()
                                .type(Type.Known.STRING)
                                .description("""
                                        当前批次内的任务唯一标识。
                                        用于任务依赖、状态跟踪和结果汇总。多个任务的 taskId
                                        不能重复。该字段可以省略，省略时由后端自动生成。
                                        必须使用 taskId，不要使用 id。
                                        """)
                                .build(),
                        "agentName",
                        Schema.builder()
                                .type(Type.Known.STRING)
                                .description(
                                        """
                                                执行该任务的子 Agent 运行时名称。
                                                必须完整、准确地使用以下名称之一：
                                                %s
                                                不允许缩写、删除应用前缀或创建新的 Agent 名称。
                                                必须使用 agentName，不要使用 agent。
                                                """.formatted(allowedAgentNames)
                                )
                                .build(),
                        "request",
                        Schema.builder()
                                .type(Type.Known.STRING)
                                .description("""
                                        下发给子 Agent 的完整任务指令。
                                        指令需要明确说明目标、检查范围、执行要求和期望输出。
                                        每个任务只描述该子 Agent 需要完成的工作，不要包含其他
                                        并行任务的内容。必须使用 request，不要使用 task 或 prompt。
                                        """)
                                .build(),
                        "dependsOn",
                        Schema.builder()
                                .type(Type.Known.ARRAY)
                                .description("""
                                        当前任务依赖的前置任务 taskId 列表。
                                        只有列表中的所有任务执行成功后，当前任务才会开始执行。
                                        引用的 taskId 必须存在于同一批次的 tasks 数组中。
                                        独立任务应省略该字段或传空数组。
                                        """)
                                .items(Schema.builder()
                                        .type(Type.Known.STRING)
                                        .description("前置任务的 taskId")
                                        .build()
                                ).build(),
                        "timeoutSeconds",
                        Schema.builder()
                                .type(Type.Known.INTEGER)
                                .description("""
                                        单个子 Agent 任务的最大执行时间，单位为秒。
                                        该字段可以省略，默认值为 120，允许范围为 10 到 600。
                                        docker pull、软件安装、镜像构建等长任务建议填写 600。
                                        超过该时间后任务会被标记为失败。
                                        """).build(),
                        "maxRetries",
                        Schema.builder()
                                .type(Type.Known.INTEGER)
                                .description("""
                                        子 Agent 执行失败后的最大重试次数，不包含首次执行。
                                        该字段可以省略，默认值为 0。允许范围为 0 到 3。
                                        对可能产生写入或变更的非幂等任务应设置为 0。
                                        """).build()
                )).required(ImmutableList.of("agentName", "request")).build();

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

            /*
             * 将模型传入的任务参数转换成 DynamicTask。
             * DynamicTask 通过 @JsonAlias 兼容：
             * id    -> taskId
             * agent -> agentName
             */
            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> objectMapper.convertValue(item, DynamicTask.class))
                    .peek(task -> {
                        /*
                         * taskId 可以省略。省略时由后端生成，保证依赖关系、
                         * 执行状态和结果汇总都有稳定的任务标识。
                         */
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                        /*
                         * 模型可能使用 YAML 中的逻辑名称：sshDiagnosisAgent
                         * 系统注册表使用的是带应用前缀的运行时名称： sshAgent_sshDiagnosisAgent
                         * 这里仅在白名单中存在唯一匹配项时进行转换。
                         */
                        task.setAgentName(resolveAllowedAgentName(task.getAgentName()));
                    }).toList();

            /*
             * 构建任务计划。
             */
            DynamicTaskPlan plan = DynamicTaskPlan.builder()
                    .tasks(tasks)
                    .maxConcurrency(args.get("maxConcurrency") instanceof Number number ? number.intValue() : 4)
                    .failFast(Boolean.TRUE.equals(args.get("failFast")))
                    .build();
            /*
             * 校验 taskId、依赖关系、循环依赖、重试次数等计划结构。
             */
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
    public List<? extends BaseTool> getAdkTool() {
        return List.of(this);
    }

    /**
     * 将模型传入的 Agent 名称解析成白名单中的运行时名称。
     *
     * <p>支持两种输入：</p>
     *
     * <ul>
     *     <li>完整运行时名称：sshAgent_sshDiagnosisAgent；</li>
     *     <li>省略应用前缀的逻辑名称：sshDiagnosisAgent。</li>
     * </ul>
     *
     * <p>只有白名单中存在唯一后缀匹配时才自动转换。没有匹配或存在多个
     * 匹配时返回原值，交给后续白名单校验拒绝，避免派发到错误 Agent。</p>
     */
    private String resolveAllowedAgentName(String requestedAgentName) {
        if (requestedAgentName == null || requestedAgentName.isBlank()) {
            return requestedAgentName;
        }
        /*
         * 已经是完整运行时名称，并且存在于白名单中。
         */
        if (allowedAgents.contains(requestedAgentName)) {
            return requestedAgentName;
        }

        String logicalName = appName + "_" + requestedAgentName;
        if (allowedAgents.contains(logicalName)) {
            return logicalName;
        }

        return requestedAgentName;
    }
}
