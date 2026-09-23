package com.jasonlat.ai.domain.agent.service;

import com.google.adk.events.Event;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;

import java.util.function.Consumer;

/**
 * 嵌套 Agent 事件发布接口。
 * <p>
 * 子 Agent、SSH 工具等组件可能运行在独立 Runner 或异步线程中，它们产生的 ADK 事件
 * 不一定天然进入父对话的 SSE 事件流。该接口提供 invocation、业务 session 和 SSH terminal
 * 三级监听器注册能力，将这些嵌套事件重新路由回当前对话的可视化事件流。
 */
public interface IAgentEventPublisher {

    /**
     * 注册父 invocation 级监听器。
     * <p>这是最精确的路由层级；重复注册同一 invocation 时，后一次注册会覆盖前一次。</p>
     *
     * @param invocationId 父 Agent 调用链的唯一标识
     * @param listener     接收嵌套事件的监听器；为空时忽略本次注册
     */
    void register(String invocationId, Consumer<AgentEventPublisher.PublishedEvent> listener);

    /**
     * 移除父 invocation 级监听器，避免 SSE 请求结束后持有失效引用。
     *
     * @param invocationId 需要解除绑定的父 Agent 调用链标识
     */
    void unregister(String invocationId);

    /**
     * 注册 ADK 业务会话级监听器。
     * <p>当子调用无法准确提供父 invocation 时，可通过该层级兜底路由事件。</p>
     *
     * @param sessionId ADK 业务会话 ID
     * @param listener  接收嵌套事件的监听器；为空时忽略本次注册
     */
    void registerSession(String sessionId, Consumer<AgentEventPublisher.PublishedEvent> listener);

    /**
     * 移除业务会话级监听器，防止并发会话复用旧事件流。
     *
     * @param sessionId 需要解除绑定的 ADK 业务会话 ID
     */
    void unregisterSession(String sessionId);

    /**
     * 注册 SSH 终端会话级监听器。
     * <p>主要用于 SSH 工具在无法获取 ToolContext 时，将工具调用和响应事件回流到当前终端对应的 SSE。</p>
     *
     * @param terminalSessionId SSH 终端会话 ID
     * @param listener          接收工具事件的监听器；为空时忽略本次注册
     */
    void registerTerminal(String terminalSessionId, Consumer<AgentEventPublisher.PublishedEvent> listener);

    /**
     * 移除 SSH 终端会话级监听器，避免终端重建或请求结束后事件串流。
     *
     * @param terminalSessionId 需要解除绑定的 SSH 终端会话 ID
     */
    void unregisterTerminal(String terminalSessionId);

    /**
     * 按 invocation 精确发布事件，不启用业务会话兜底。
     *
     * @param invocationId 父 Agent 调用链的唯一标识
     * @param event        需要转发的 ADK 事件
     * @param nested       {@code true} 表示事件来自嵌套 Agent；前端可据此展示来源
     */
    void publish(String invocationId, Event event, boolean nested);

    /**
     * 按 invocation 优先、业务会话兜底的两级路由发布事件。
     *
     * @param invocationId 父 Agent 调用链的唯一标识；为空时跳过 invocation 路由
     * @param sessionId    ADK 业务会话 ID；仅当 invocation 未命中监听器时使用
     * @param event        需要转发的 ADK 事件
     * @param nested       {@code true} 表示事件来自嵌套 Agent
     */
    void publish(String invocationId, String sessionId, Event event, boolean nested);

    /**
     * 在当前仅存在一个业务会话监听器时保守发布事件。
     * <p>用于 ToolContext 缺失或父子调用链无法建立的场景；存在多个会话时不会投递，避免串流。</p>
     *
     * @param event  需要转发的 ADK 事件
     * @param nested {@code true} 表示事件来自嵌套 Agent
     */
    void publishToOnlyActiveSession(Event event, boolean nested);

    /**
     * 按 SSH 终端会话发布事件。
     *
     * @param terminalSessionId SSH 终端会话 ID
     * @param event             需要转发的工具调用或响应事件
     * @param nested            {@code true} 表示事件来自嵌套 Agent 或工具
     */
    void publishToTerminal(String terminalSessionId, Event event, boolean nested);

    /**
     * 执行最终投递，供具体实现复用。
     * <p>监听器为空时忽略本次事件；监听器异常的处理策略由具体实现决定。</p>
     *
     * @param listener   已命中的事件监听器
     * @param event      需要转发的 ADK 事件
     * @param nested     {@code true} 表示事件来自嵌套 Agent 或工具
     * @param routingKey 当前命中的路由标识，可由实现用于诊断日志
     */
    default void publish(
            Consumer<AgentEventPublisher.PublishedEvent> listener,
            Event event, boolean nested, String routingKey) {

        if (listener != null) {
            listener.accept(new AgentEventPublisher.PublishedEvent(event, nested));
        }
    }

}
