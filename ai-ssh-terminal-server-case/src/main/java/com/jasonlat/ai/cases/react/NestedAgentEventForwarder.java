package com.jasonlat.ai.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将独立 Runner 和工具主动推送的 ADK 事件转换成 {@code /chat_stream} 使用的 JSON 行。
 * <p>
 * 同一个真实动作可能同时出现在 Runner 原始事件和工具主动推送事件中，因此本类还负责：
 * 调用/结果去重、缺失响应 ID 的配对、子 Agent 生命周期识别，以及流式末帧全文去重。
 * 本对象按一次 HTTP 流创建，内部状态不能跨会话复用。
 */
@Slf4j
public final class NestedAgentEventForwarder {

    private final ObjectMapper objectMapper;
    /** 已发送到前端的调用 ID，防止 Runner 与工具主动上报产生两个 tool_call。 */
    private final Set<String> sentCalls = ConcurrentHashMap.newKeySet();
    /** 已发送到前端的结果 ID，防止同一个 functionResponse 重复更新 UI。 */
    private final Set<String> sentResults = ConcurrentHashMap.newKeySet();
    /** 按“子 Agent + 工具名”保存待配对调用；兼容上游 FunctionResponse 缺少 id 的情况。 */
    private final Map<String, ConcurrentLinkedDeque<String>> pending = new ConcurrentHashMap<>();
    /** 当前子 Agent 的模型回复片段，用来识别流式结束时可能重复上报的完整文本。 */
    private final Map<String, StringBuilder> currentAgentTexts = new ConcurrentHashMap<>();
    /** 为上游缺失 ID 的事件生成仅在当前流内唯一的兜底编号。 */
    private final AtomicInteger missingIds = new AtomicInteger();

    /** 每条 /chat_stream 创建一个实例，使去重集合和文本累加器严格隔离。 */
    public NestedAgentEventForwarder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 转发一个已路由到当前父会话的事件。
     * <p>
     * 处理顺序必须保持为“模型文字 -> FunctionCall/FunctionResponse”，这样一个 ADK Event
     * 同时带文字和工具动作时，前端仍能按真实时间顺序插入消息片段。
     */
    public void forward(ResponseBodyEmitter emitter, AgentEventPublisher.PublishedEvent published) {
        Event event = published.event();
        if (event == null) {
            return;
        }
        String agentCallId = published.agentCallId();
        String sourceAgent = nonBlank(published.sourceAgent(), event.author());
        forwardAgentText(emitter, event, agentCallId, published.parentToolCallId(), sourceAgent);
        for (FunctionCall call : event.functionCalls()) {
            String name = call.name().orElse("unknown_tool");
            String rawId = call.id().filter(id -> !id.isBlank())
                    .orElseGet(() -> "nested_call_" + missingIds.incrementAndGet());
            String id = displayId(agentCallId, rawId, name, sourceAgent);
            if (!sentCalls.add(id)) {
                continue; // ADK 事件和工具主动推送可能报告同一次调用。
            }
            // 先登记调用，后续没有 response.id 时按同一 Agent、同一工具的调用顺序配对。
            pending.computeIfAbsent(queueKey(agentCallId, name), ignored -> new ConcurrentLinkedDeque<>())
                    .addLast(id);
            Map<String, Object> args = call.args().orElse(Map.of());
            // 派发服务用“ID=agentCallId、名称=sourceAgent”的合成函数事件表达子 Agent 生命周期。
            boolean agentLifecycle = isAgentLifecycle(agentCallId, rawId, name, sourceAgent);
            Map<String, Object> payload = base(agentLifecycle ? "agent_start" : "tool_call",
                    agentCallId, published.parentToolCallId(), sourceAgent);
            payload.put(agentLifecycle ? "agentName" : "toolName", name);
            payload.put(agentLifecycle ? "agentCallId" : "toolCallId", id);
            if (agentLifecycle) {
                payload.put("task", string(args.get("request")));
            } else {
                payload.put("command", string(args.get("command")));
                payload.put("toolArgs", args);
            }
            payload.put("status", "running");
            send(emitter, payload);
        }

        for (FunctionResponse response : event.functionResponses()) {
            String name = response.name().orElse("unknown_tool");
            String rawId = response.id().filter(id -> !id.isBlank()).orElse(null);
            // 优先使用响应自己的 ID；缺失时从 pending 队列取出最早的同名调用。
            String id = rawId == null
                    ? pending.computeIfAbsent(queueKey(agentCallId, name), ignored -> new ConcurrentLinkedDeque<>())
                            .pollFirst()
                    : displayId(agentCallId, rawId, name, sourceAgent);
            if (id == null) {
                id = displayId(agentCallId, "orphan_result_" + missingIds.incrementAndGet(), name, sourceAgent);
            }
            if (!sentResults.add(id)) {
                continue;
            }
            Map<String, Object> result = response.response().orElse(Map.of());
            boolean success = !Boolean.FALSE.equals(result.get("success")) && result.get("error") == null;
            boolean agentLifecycle = isAgentLifecycle(agentCallId, rawId, name, sourceAgent);
            Map<String, Object> payload = base(agentLifecycle ? "agent_result" : "tool_result",
                    agentCallId, published.parentToolCallId(), sourceAgent);
            payload.put(agentLifecycle ? "agentName" : "toolName", name);
            payload.put(agentLifecycle ? "agentCallId" : "toolCallId", id);
            payload.put("status", success ? "success" : "error");
            Object output = result.containsKey("error") ? result.get("error")
                    : result.containsKey("output") ? result.get("output")
                    : result.getOrDefault("result", result);
            payload.put("content", string(output));
            if (!agentLifecycle) {
                payload.put("command", string(result.get("command")));
            }
            send(emitter, payload);
        }

        // 工具调用/结果划分模型回复轮次，下一段文本应在工具之后单独展示。
        if (agentCallId != null && (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty())) {
            currentAgentTexts.remove(agentCallId);
        }
    }

    /**
     * 提取子 Agent 的模型文本并只发送本次新增部分。
     * 主 Agent 文本由 AiCallNode 处理；这里要求 agentCallId 非空，避免重复转发顶层回复。
     */
    private void forwardAgentText(ResponseBodyEmitter emitter, Event event, String agentCallId,
                                  String parentToolCallId, String sourceAgent) {
        if (agentCallId == null || agentCallId.isBlank() || event.content().isEmpty()) {
            return;
        }
        Content content = event.content().get();
        String role = content.role().orElse("");
        if (!("model".equals(role) || "assistant".equals(role)
                || (role.isBlank() && sourceAgent != null && sourceAgent.equals(event.author())))) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Part part : content.parts().orElse(List.of())) {
            part.text().ifPresent(text::append);
        }
        if (text.isEmpty()) {
            return;
        }
        String chunk = text.toString();
        StringBuilder current = currentAgentTexts.computeIfAbsent(agentCallId, ignored -> new StringBuilder());
        String delta;
        synchronized (current) {
            String previous = current.toString();
            // ADK 流式末尾可能再给一条完整内容；只发尚未展示的后缀。
            if (!event.partial().orElse(false) && chunk.startsWith(previous) && !previous.isEmpty()) {
                delta = chunk.substring(previous.length());
            } else if (!event.partial().orElse(false) && previous.startsWith(chunk)) {
                delta = "";
            } else {
                delta = chunk;
            }
            current.append(delta);
        }
        if (!delta.isEmpty()) {
            Map<String, Object> payload = base("agent_text", agentCallId, parentToolCallId, sourceAgent);
            payload.put("content", delta);
            send(emitter, payload);
            log.debug("子 Agent 文本增量已转发 | agentCallId:{} | sourceAgent:{} | chars:{}",
                    agentCallId, sourceAgent, delta.length());
        }
    }

    /** 判断当前合成函数事件是在描述子 Agent 本身，而不是子 Agent 调用的普通工具。 */
    private boolean isAgentLifecycle(String agentCallId, String rawId, String name, String sourceAgent) {
        return agentCallId != null && agentCallId.equals(rawId) && name.equals(sourceAgent);
    }

    /**
     * 普通工具 ID 增加 agentCallId 前缀，避免并发子 Agent 使用相同上游 callId 时相互覆盖。
     * 生命周期事件必须保留原 agentCallId，前端才能更新同一张子 Agent 卡片。
     */
    private String displayId(String agentCallId, String rawId, String name, String sourceAgent) {
        return isAgentLifecycle(agentCallId, rawId, name, sourceAgent) || agentCallId == null
                ? rawId : agentCallId + "/" + rawId;
    }

    /** FunctionResponse 缺失 ID 时使用的顺序配对键。 */
    private String queueKey(String agentCallId, String name) {
        return (agentCallId == null ? "root" : agentCallId) + "|" + name;
    }

    /** 创建所有嵌套事件共享的关联字段，前端据此建立父子层级。 */
    private Map<String, Object> base(String event, String agentCallId,
                                     String parentToolCallId, String sourceAgent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("nested", true);
        payload.put("sourceAgent", sourceAgent);
        payload.put("agentCallId", agentCallId);
        payload.put("parentToolCallId", parentToolCallId);
        return payload;
    }

    /**
     * 以一行一个 JSON 的方式写入长连接。多个子 Agent 可并发发布，必须按 emitter 串行写，
     * 否则两段 JSON 可能交叉，导致前端无法按行解析。
     */
    private void send(ResponseBodyEmitter emitter, Map<String, Object> payload) {
        try {
            synchronized (emitter) {
                emitter.send(objectMapper.writeValueAsString(payload) + "\n");
            }
        } catch (Exception exception) {
            log.warn("子 Agent SSE 事件发送失败 | event={} | agentCallId={}",
                    payload.get("event"), payload.get("agentCallId"), exception);
        }
    }

    /** 优先采用发布器显式携带的 Agent 名称，缺失时回退到 ADK event.author。 */
    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** 将 Map/List 等结果稳定转换成可放入前端 content 字段的字符串。 */
    private String string(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }
}
