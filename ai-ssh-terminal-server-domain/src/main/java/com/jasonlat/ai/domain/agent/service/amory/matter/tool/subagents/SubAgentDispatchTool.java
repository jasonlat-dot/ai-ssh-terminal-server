package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.factory.CustomRunnerFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 子 Agent 派发工具（单个） - 把一个子 Agent 包装成 ADK FunctionTool 供父 Agent 调用。
 * <p>
 * 配置了 subAgents 的父 Agent 装配时，每个子 Agent 都会被包装为本工具
 * （工具名/描述即子 Agent 的 name/description，LLM 通过函数调用触发派发）。
 * 派发过程：为子 Agent 创建独立 Runner 与会话，同步收集全部事件，
 * 取最后一个事件的文本作为执行结果返回给父 Agent。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），需传入 runnerFactory 手工构造 Runner。
 */
@Slf4j
public class SubAgentDispatchTool extends BaseTool implements AdkToolProvider {

    /** 被包装、派发的子 Agent */
    private final BaseAgent subAgent;
    /** Runner 工厂，为子 Agent 构建独立执行器 */
    private final CustomRunnerFactory runnerFactory;

    public SubAgentDispatchTool(BaseAgent subAgent, CustomRunnerFactory runnerFactory) {
        // 这一行就是申明工具名称和描述。
        super(subAgent.name(), subAgent.description());
        this.subAgent = subAgent;
        this.runnerFactory = runnerFactory;
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
     * 执行派发：以独立会话运行子 Agent，阻塞收集全部事件后汇总结果。
     * 执行异常时返回 {success:false, error:...}，由父 Agent 感知并决定下一步。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {

        String request = String.valueOf(args.getOrDefault("request", ""));
        String invocationId = UUID.randomUUID().toString();

        Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
        String terminalSessionId = terminalValue instanceof String value ? value : null;
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            return Single.just(Map.of(
                    "success", false,
                    "error", "父 Agent 未绑定 SSH 终端会话"));
        }
        String userId = toolContext.userId();
        String childSessionId = "subAgent-" + invocationId;
        Runner runner = runnerFactory.create(subAgent, subAgent.name(), List.of());

        Map<String, Object> initialState = new ConcurrentHashMap<>();
        initialState.put(TERMINAL_SESSION_STATE_KEY, terminalSessionId);

        log.info("子Agent派发开始 | subAgent:{} | request:{} | invocationId:{}", subAgent.name(), request, invocationId);
        Content content = Content.fromParts(Part.fromText(request));

        RunConfig runConfig = RunConfig.builder()
                .autoCreateSession(true)
                .build();

        return Single.defer(() ->
                        runner.sessionService().createSession(
                                runner.appName(), userId, initialState, childSessionId))
                .flatMapPublisher(session -> {
                    log.info("子Agent Session 创建成功 | sessionKey:{} | terminalSessionId:{}",
                            session.sessionKey(), terminalSessionId);
                    return runner.runAsync(userId, childSessionId, content, runConfig);
                })
                .toList()
                .map(events -> toResult(events, invocationId))
                .onErrorReturn(error -> {
                    log.error("子Agent派发异常 | subAgent:{} | invocationId:{}",
                            subAgent.name(), invocationId, error);
                    return Map.of(
                            "success", false,
                            "error", String.valueOf(error.getMessage()));
                });

    }

    /** 取最后一个事件的文本内容作为子 Agent 的最终回复 */
    private Map<String, Object> toResult(List<Event> events, String invocationId) {
        String result = events.isEmpty() ? "" :
                events.get(events.size() - 1).content()
                .map(Content::text)
                .orElse("");

        log.info("子Agent派发完成 | subAgent:{} | resultLength:{} | invocationId:{}", subAgent.name(), result.length(), invocationId);

        return ImmutableMap.of(
                "success", true,
                "result", result);
    }

    @Override
    public List<? extends BaseTool> getTools() {
        return List.of(this);
    }
}
