package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.factory.CustomRunnerFactory;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.register.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.support.SubAgentResultText;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


/**
 * 子 Agent 派发工具（单个） - 把一个子 Agent 包装成 ADK FunctionTool 供父 Agent 调用。
 * <p>
 * 配置了 subAgents 的父 Agent 装配时，每个子 Agent 都会被包装为本工具
 * （工具名/描述即子 Agent 的 name/description，LLM 通过函数调用触发派发）。
 * 派发过程：为子 Agent 创建独立 Runner 与会话，逐事件回流文本和工具活动；同时收集事件，
 * 在子 Agent 结束后提取最终模型回复作为工具结果返回父 Agent。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），需传入 runnerFactory 手工构造 Runner。
 */
@Slf4j
public class SubAgentDispatchTool extends BaseTool implements AdkToolProvider {

    /** 被包装、派发的子 Agent */
    private final BaseAgent subAgent;
    /** Runner 工厂，为子 Agent 构建独立执行器 */
    private final CustomRunnerFactory runnerFactory;

    /**
     * 事件发布器为可选依赖，兼容旧的直接构造方式和不需要 UI 透传的测试场景。
     */
    private final AgentEventPublisher agentEventPublisher;

    /**
     * 兼容旧调用方式；不注入事件发布器时派发仍可正常执行，只是不产生嵌套事件。
     */
    public SubAgentDispatchTool(BaseAgent subAgent, CustomRunnerFactory runnerFactory) {
        this(subAgent, runnerFactory, null);
    }

    /**
     * 创建单个子 Agent 派发工具。
     *
     * @param subAgent            被包装的子 Agent
     * @param runnerFactory       子 Agent Runner 工厂
     * @param agentEventPublisher 可选事件发布器；为空时不回流子 Runner 事件
     */
    public SubAgentDispatchTool(BaseAgent subAgent,
                                CustomRunnerFactory runnerFactory,
                                AgentEventPublisher agentEventPublisher) {
        super(subAgent.name(), subAgent.description());
        this.subAgent = subAgent;
        this.runnerFactory = runnerFactory;
        this.agentEventPublisher = agentEventPublisher;
    }


    /**
     * 声明工具的函数签名：入参仅一个必填字符串 request（下发给子 Agent 的任务指令）。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(ImmutableMap.of(
                                "request",
                                Schema.builder()
                                        .type(Type.Known.STRING)
                                        .description("要完成的完整任务指令")
                                        .build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 执行派发：以独立会话运行子 Agent；Runner 事件实时发布给父对话，完成后再汇总结果。
     * 执行异常时返回 {success:false, error:...}，由父 Agent 感知并决定下一步。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {

        String request = String.valueOf(args.getOrDefault("request", ""));
        String invocationId = UUID.randomUUID().toString();
        String parentInvocationId = toolContext.invocationId();
        Object rootSessionValue = toolContext.state().get(PARENT_SESSION_ID);
        String parentSessionId = rootSessionValue instanceof String value ? value : toolContext.sessionId();
        Object cancellationValue = toolContext.state().get(RUN_CANCELLATION);
        AgentRunCancellation cancellation = cancellationValue instanceof AgentRunCancellation value ? value : null;
        String functionCallId = toolContext.functionCallId().orElse("call_" + invocationId);
        publishSyntheticFunctionCall(parentInvocationId, parentSessionId, functionCallId, request);

        Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
        String terminalSessionId = terminalValue instanceof String value ? value : null;
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            Map<String, Object> result = Map.of(
                    "success", false,
                    "error", "父 Agent 未绑定 SSH 终端会话");
            publishSyntheticFunctionResponse(parentInvocationId, parentSessionId, functionCallId, result);
            return Single.just(result);
        }
        String userId = toolContext.userId();
        String childSessionId = "subAgent-" + invocationId;
        Runner runner = runnerFactory.create(subAgent, subAgent.name(), List.of());

        log.info("子Agent派发开始 | subAgent:{} | request:{} | invocationId:{}", subAgent.name(), request, invocationId);
        Content content = Content.fromParts(Part.fromText(request));

        // 独立子 Session 不共享父 Session state，需要显式复制终端、取消句柄和事件关联 ID。
        // agentCallId 使用父工具 functionCallId，使整个子 Agent 生命周期可以更新同一张 UI 卡片。
        ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
        initialState.put(TERMINAL_SESSION_STATE_KEY, terminalSessionId);
        initialState.put(RUNNER_AGENT_NAME, subAgent.name());
        initialState.put(NESTED_AGENT_CALL_ID, functionCallId);
        initialState.put(PARENT_TOOL_CALL_ID, functionCallId);
        if (cancellation != null) initialState.put(RUN_CANCELLATION, cancellation);
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            initialState.put(PARENT_SESSION_ID, parentSessionId);
        }
        RunConfig runConfig = RunConfig.builder()
                .autoCreateSession(false)
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        AtomicReference<Disposable> subscription = new AtomicReference<>();
        return Single.defer(() -> {
                    if (cancellation != null) cancellation.throwIfCancelled();
                    return runner.sessionService().createSession(
                            runner.appName(), userId, initialState, childSessionId);
                })
                .flatMapPublisher(session -> {
                    if (cancellation != null) cancellation.throwIfCancelled();
                    log.info("子Agent Session 创建成功 | sessionKey:{} | terminalSessionId:{}",
                            session.sessionKey(), terminalSessionId);
                    return runner.runAsync(userId, childSessionId, content, runConfig);
                })
                .doOnNext(event -> {
                    if (cancellation != null) cancellation.throwIfCancelled();
                    if (agentEventPublisher != null) {
                        // 不等待 toList 完成：模型文本和工具事件到达一条就立即回流一条。
                        publishEvent(parentSessionId, event, functionCallId);
                    }
                })
                .toList()
                .map(events -> {
                    if (cancellation != null) cancellation.throwIfCancelled();
                    Map<String, Object> result = toResult(events, invocationId);
                    publishSyntheticFunctionResponse(parentInvocationId, parentSessionId, functionCallId, result);
                    return result;
                })
                .onErrorReturn(error -> {
                    log.error("子Agent派发异常 | subAgent:{} | invocationId:{}",
                            subAgent.name(), invocationId, error);
                    Map<String, Object> errResult = Map.of(
                            "success", false,
                            "error", String.valueOf(error.getMessage()));
                    publishSyntheticFunctionResponse(parentInvocationId, parentSessionId, functionCallId, errResult);
                    return errResult;
                })
                .doOnSubscribe(disposable -> {
                    subscription.set(disposable);
                    if (cancellation != null) cancellation.registerSubscription(disposable);
                })
                .doFinally(() -> {
                    if (cancellation != null) cancellation.unregisterSubscription(subscription.get());
                });

    }

    /**
     * 构造并发布子 Agent 的合成 function call 事件，补齐父 Runner 可能缺失的工具调用事件。
     */
    private void publishSyntheticFunctionCall(
            String parentInvocationId, String parentSessionId, String functionCallId, String request) {
        if (agentEventPublisher == null) {
            return;
        }
        FunctionCall call = FunctionCall.builder()
                .id(functionCallId)
                .name(subAgent.name())
                .args(Map.of("request", request))
                .build();
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(parentInvocationId)
                .author(subAgent.name())
                .content(Content.fromParts(Part.builder().functionCall(call).build()))
                .build();
        publishEvent(parentSessionId, event, functionCallId);
    }

    /**
     * 构造并发布子 Agent 的合成 function response 事件；成功和失败都会通知前端。
     */
    private void publishSyntheticFunctionResponse(
            String parentInvocationId,
            String parentSessionId,
            String functionCallId,
            Map<String, Object> result) {
        if (agentEventPublisher == null) {
            return;
        }
        FunctionResponse response = FunctionResponse.builder()
                .id(functionCallId)
                .name(subAgent.name())
                .response(result)
                .build();
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(parentInvocationId)
                .author(subAgent.name())
                .content(Content.fromParts(Part.builder().functionResponse(response).build()))
                .build();
        publishEvent(parentSessionId, event, functionCallId);
    }

    /**
     * 按父 invocation 优先、业务会话次之的方式路由事件；两者均不可用时保守兜底。
     */
    private void publishEvent(String parentSessionId, Event event, String agentCallId) {
        agentEventPublisher.publishToSession(parentSessionId, event,
                agentCallId, agentCallId, subAgent.name());
    }


    /** 流式末尾可能是 usage/结束事件，需从模型文本片段还原最终回复。 */
    private Map<String, Object> toResult(List<Event> events, String invocationId) {
        String result = SubAgentResultText.finalReply(events, subAgent.name());

        log.info("子Agent派发完成 | subAgent:{} | resultLength:{} | invocationId:{}", subAgent.name(), result.length(), invocationId);

        return ImmutableMap.of(
                "success", true,
                "result", result);
    }

    @Override
    public List<? extends BaseTool> getAdkTool() {
        return List.of(this);
    }
}
