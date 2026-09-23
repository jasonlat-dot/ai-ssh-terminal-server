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

/** 将独立 Runner 和工具推送的 ADK 事件转换成当前 /chat_stream 使用的 JSON 行。 */
@Slf4j
public final class NestedAgentEventForwarder {

    private final ObjectMapper objectMapper;
    private final Set<String> sentCalls = ConcurrentHashMap.newKeySet();
    private final Set<String> sentResults = ConcurrentHashMap.newKeySet();
    private final Map<String, ConcurrentLinkedDeque<String>> pending = new ConcurrentHashMap<>();
    /** 当前子 Agent 的模型回复片段，用来识别流式结束时可能重复上报的完整文本。 */
    private final Map<String, StringBuilder> currentAgentTexts = new ConcurrentHashMap<>();
    private final AtomicInteger missingIds = new AtomicInteger();

    public NestedAgentEventForwarder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
            pending.computeIfAbsent(queueKey(agentCallId, name), ignored -> new ConcurrentLinkedDeque<>())
                    .addLast(id);
            Map<String, Object> args = call.args().orElse(Map.of());
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

    private boolean isAgentLifecycle(String agentCallId, String rawId, String name, String sourceAgent) {
        return agentCallId != null && agentCallId.equals(rawId) && name.equals(sourceAgent);
    }

    private String displayId(String agentCallId, String rawId, String name, String sourceAgent) {
        return isAgentLifecycle(agentCallId, rawId, name, sourceAgent) || agentCallId == null
                ? rawId : agentCallId + "/" + rawId;
    }

    private String queueKey(String agentCallId, String name) {
        return (agentCallId == null ? "root" : agentCallId) + "|" + name;
    }

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

    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

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
