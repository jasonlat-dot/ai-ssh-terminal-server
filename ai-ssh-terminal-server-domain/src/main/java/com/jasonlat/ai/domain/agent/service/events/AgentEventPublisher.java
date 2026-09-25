package com.jasonlat.ai.domain.agent.service.events;

import com.google.adk.events.Event;
import com.jasonlat.ai.domain.agent.service.IAgentEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Agent 事件的进程内路由中心。
 * <p>
 * 子 Runner 与工具通常运行在独立线程/独立 ADK Session 中，不能直接持有 HTTP 层的
 * {@code ResponseBodyEmitter}。发布方只提交 ADK {@link Event} 和关联 ID，本类再根据
 * invocation、业务会话或终端会话找到 Case 层注册的监听器。
 * <p>
 * 本类只负责“把事件送到正确的对话”，不负责把 ADK Event 转成前端 JSON；协议转换由
 * Case 层的 {@code NestedAgentEventForwarder} 完成。
 */
@Component
@Slf4j
public class AgentEventPublisher implements IAgentEventPublisher {

    /**
     * 按父 invocation 路由事件；适用于能够从 ToolContext 获取父调用链的子 Agent。
     */
    private final Map<String, Consumer<PublishedEvent>> listeners = new ConcurrentHashMap<>();

    /**
     * 按对话业务会话路由事件。当前子 Agent 链路的主通道：父请求在进入 RootNode 前注册，
     * 子 Runner 和 executeCommand 使用相同 sessionId 将事件送回原来的 HTTP 流。
     */
    private final Map<String, Consumer<PublishedEvent>> sessionListeners = new ConcurrentHashMap<>();

    /**
     * 按 SSH 终端会话路由事件，用于 executeCommand 这类工具直接产生的事件。
     */
    private final Map<String, Consumer<PublishedEvent>> terminalListeners = new ConcurrentHashMap<>();

    /**
     * 注册父 invocation 级监听器；重复注册时以后一次为准，空参数会被忽略。
     */
    @Override
    public void register(String invocationId, Consumer<PublishedEvent> listener) {
        if (invocationId != null && !invocationId.isBlank() && listener != null) {
            listeners.put(invocationId, listener);
            log.debug("注册 invocation 事件监听器 | invocationId={}", invocationId);
        } else {
            log.debug("忽略无效的 invocation 事件监听器注册 | invocationId={}", invocationId);
        }
    }

    /**
     * 移除父 invocation 级监听器，避免 SSE 请求结束后持有失效引用。
     */
    @Override
    public void unregister(String invocationId) {
        if (invocationId != null && !invocationId.isBlank()) {
            listeners.remove(invocationId);
            log.debug("移除 invocation 事件监听器 | invocationId={}", invocationId);
        }
    }

    /**
     * 注册 ADK 业务会话级监听器，作为 invocation 精确路由不可用时的兜底通道。
     */
    @Override
    public void registerSession(String sessionId, Consumer<PublishedEvent> listener) {
        if (sessionId != null && !sessionId.isBlank() && listener != null) {
            sessionListeners.put(sessionId, listener);
            log.debug("注册 session 事件监听器 | sessionId={}", sessionId);
        } else {
            log.debug("忽略无效的 session 事件监听器注册 | sessionId={}", sessionId);
        }
    }

    /**
     * 移除业务会话级监听器，防止并发会话复用旧事件流。
     */
    @Override
    public void unregisterSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionListeners.remove(sessionId);
            log.debug("移除 session 事件监听器 | sessionId={}", sessionId);
        }
    }

    /**
     * 注册 SSH 终端级监听器，供 SSH 工具在无 ToolContext 时回流事件。
     */
    @Override
    public void registerTerminal(String terminalSessionId, Consumer<PublishedEvent> listener) {
        if (terminalSessionId != null && !terminalSessionId.isBlank() && listener != null) {
            terminalListeners.put(terminalSessionId, listener);
            log.debug("注册 terminal 事件监听器 | terminalSessionId={}", terminalSessionId);
        } else {
            log.debug("忽略无效的 terminal 事件监听器注册 | terminalSessionId={}", terminalSessionId);
        }
    }

    /**
     * 移除 SSH 终端级监听器，避免终端重建或请求结束后事件串流。
     */
    @Override
    public void unregisterTerminal(String terminalSessionId) {
        if (terminalSessionId != null && !terminalSessionId.isBlank()) {
            terminalListeners.remove(terminalSessionId);
            log.debug("移除 terminal 事件监听器 | terminalSessionId={}", terminalSessionId);
        }
    }

    /**
     * 按 invocation 路由事件，等价于不提供业务会话兜底 ID 的完整发布方法。
     */
    @Override
    public void publish(String invocationId, Event event, boolean nested) {
        publish(invocationId, null, event, nested);
    }

    /**
     * 按 invocation、session 两级路由事件；invocation 更精确，session 用于父调用尚未登记时兜底。
     */
    @Override
    public void publish(String invocationId, String sessionId, Event event, boolean nested) {
        if (event == null) {
            log.debug("忽略空事件 | invocationId={} | sessionId={}", invocationId, sessionId);
            return;
        }
        Consumer<PublishedEvent> listener = invocationId == null || invocationId.isBlank()
                ? null
                : listeners.get(invocationId);
        if (listener == null && sessionId != null && !sessionId.isBlank()) {
            listener = sessionListeners.get(sessionId);
        }
        if (listener == null) {
            log.debug("嵌套事件未命中监听器 | invocationId={} | sessionId={} | eventId={} | author={}",
                    invocationId, sessionId, event.id(), event.author());
        }
        publish(listener, event, nested, invocationId);
    }

    /**
     * Spring AI 的 ADK ToolConverter 在部分版本中不会注入 ToolContext。
     * 当工具无法提供父 invocation/session 时，仅在当前存在唯一 SSE 会话时兜底投递，
     * 避免并发对话之间串流。
     */
    @Override
    public void publishToOnlyActiveSession(Event event, boolean nested) {
        if (event == null || sessionListeners.size() != 1) {
            log.debug("跳过唯一活跃会话兜底 | eventId={} | activeSessionCount={}",
                    event == null ? null : event.id(), sessionListeners.size());
            return;
        }
        publish(sessionListeners.values().iterator().next(), event, nested, "active-session");
    }

    /**
     * 按 SSH 终端会话路由事件；该路由专门服务于无法获取 ToolContext 的工具事件。
     */
    @Override
    public void publishToTerminal(String terminalSessionId, Event event, boolean nested) {
        if (event == null || terminalSessionId == null || terminalSessionId.isBlank()) {
            log.debug("忽略无效的终端事件 | terminalSessionId={} | eventId={}",
                    terminalSessionId, event == null ? null : event.id());
            return;
        }
        Consumer<PublishedEvent> listener = terminalListeners.get(terminalSessionId);
        if (listener == null) {
            log.debug("终端事件未命中监听器 | terminalSessionId={} | eventId={} | author={}",
                    terminalSessionId, event.id(), event.author());
        }
        publish(listener, event, nested, terminalSessionId);
    }

    /**
     * 按本次对话的业务会话投递子 Agent 活动。
     * <p>
     * 不能使用终端 ID 作为此处的主路由键：同一个 SSH 终端可能同时承载多条 AI 对话。
     * 三个关联字段不会参与路由，但会随事件传给 Case 层，供前端恢复
     * “父派发工具 -> 子 Agent -> 子 Agent 工具”的层级关系。
     *
     * @param sessionId       父对话 sessionId，决定事件进入哪一条 /chat_stream
     * @param event           子 Runner 或工具产生的标准 ADK 事件
     * @param agentCallId     一次子 Agent 执行的唯一 ID；同一子 Agent 的文本和工具共享该值
     * @param parentToolCallId 触发本次派发的父工具调用 ID
     * @param sourceAgent     事件来源子 Agent 的名称
     */
    public void publishToSession(String sessionId, Event event, String agentCallId,
                                 String parentToolCallId, String sourceAgent) {
        if (sessionId == null || sessionId.isBlank() || event == null) {
            log.debug("跳过未绑定业务会话的子 Agent 事件 | sessionId={} | eventId={}",
                    sessionId, event == null ? null : event.id());
            return;
        }
        Consumer<PublishedEvent> listener = sessionListeners.get(sessionId);
        if (listener == null) {
            log.debug("子 Agent 事件未命中会话监听器 | sessionId={} | eventId={}", sessionId, event.id());
            return;
        }
        try {
            listener.accept(new PublishedEvent(event, true, agentCallId, parentToolCallId, sourceAgent));
        } catch (RuntimeException exception) {
            log.warn("子 Agent 事件投递失败 | sessionId={} | eventId={}", sessionId, event.id(), exception);
        }
    }

    /**
     * 最终投递入口；这里统一记录路由结果，并保证单个监听器异常不会中断 Agent 主流程。
     * `nested = true`：当前这个事件，**是子 Agent（child Agent）产生 / 发出来的
     * `nested = false`：事件是**顶层主 Agent 自己产生**的，不是子 Agent
     */
    @Override
    public void publish(Consumer<PublishedEvent> listener, Event event,
                        boolean nested, String routingKey) {

        if (listener == null || event == null) {
            return;
        }

        try {
            listener.accept(new PublishedEvent(event, nested));
            log.debug("嵌套事件发布完成 | routingKey={} | nested={} | eventId={} | invocationId={} | author={}",
                    routingKey, nested, event.id(), event.invocationId(), event.author());
        } catch (RuntimeException exception) {
            log.warn("嵌套事件监听器执行失败 | routingKey={} | nested={} | eventId={} | invocationId={} | author={}",
                    routingKey, nested, event.id(), event.invocationId(), event.author(), exception);
        }
    }

    /**
     * 对外发布的不可变事件包装。
     *
     * @param event            原始 ADK 事件
     * @param nested           是否来自嵌套 Agent
     * @param agentCallId      子 Agent 执行 ID，前端用它归组 agent_text/tool_call/tool_result
     * @param parentToolCallId 父派发工具调用 ID，用于保留父子调用关系
     * @param sourceAgent      事件来源 Agent 名称，用于展示和兜底识别生命周期事件
     */
    public record PublishedEvent(Event event, boolean nested, String agentCallId,
                                 String parentToolCallId, String sourceAgent) {
        public PublishedEvent(Event event, boolean nested) {
            this(event, nested, null, null, null);
        }
    }

}
