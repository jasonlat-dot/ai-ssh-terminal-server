package com.jasonlat.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import com.jasonlat.ai.trigger.api.IAgentService;
import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.dto.enums.ReActEventTypeEnum;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author jasonlat
 * 2026-04-04  14:32
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AgentController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase agentReActServiceCase;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * ReAct UI 实时可视化事件发布器。
     * <p>
     * ADK 原始事件只能沿当前 Runner 返回，而子 Agent、批量派发和 SSH 工具可能在独立 Runner/线程中执行。
     * 这里通过发布器把这些“嵌套事件”重新挂回当前 SSE 流，前端即可看到完整的工具调用与子 Agent 执行过程。
     */
    @Resource
    private AgentEventPublisher agentEventPublisher;

    @Override
    @RequestMapping(value = "/validateSessionId", method = RequestMethod.POST)
    public Response<Boolean> validateSessionId(@RequestBody SessionDataRequest request) {
        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            Objects.requireNonNull(request.getSessionId(), "会话ID不能为空");

            boolean validated = chatService.validateSession(request.getAgentId(), request.getUserId(), request.getSessionId());
            log.info("验证会话 agentId:{} userId:{} sessionId:{} 结果：{}", request.getAgentId(), request.getUserId(), request.getSessionId(),  validated);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(validated)
                    .build();
        } catch (AppException e) {
            log.error("验证会话异常", e);
            return Response.<Boolean>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("验证会话失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    @RequestMapping(value = "/query_ai_agent_config_list", method = RequestMethod.GET)
    public Response<List<AgentConfigResponse>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表 -- start");
            List<AiAgentConfigTableVO.AgentDefinition> agentDefinitions = chatService.queryAgentConfigList();
            List<AgentConfigResponse> agentConfigResponses = agentDefinitions.stream().map(agentDefinition -> {
                AgentConfigResponse agentConfigResponse = new AgentConfigResponse();
                agentConfigResponse.setAgentId(agentDefinition.getAgentId());
                agentConfigResponse.setAgentName(agentDefinition.getAgentName());
                agentConfigResponse.setAgentDesc(agentDefinition.getAgentDesc());
                return agentConfigResponse;
            }).toList();
            log.info("查询智能体配置列表 -- end");
            return Response.success(ResponseCode.SUCCESS.getInfo(), agentConfigResponses);
        } catch (AppException appException) {
            log.error("查询智能体配置列表异常 -- error：", appException);
            return Response.error(appException.getInfo());
        } catch (Exception e) {
            log.error("查询智能体配置列表失败 -- error: ", e);
            return Response.error(ResponseCode.UN_ERROR.getInfo());
        }
    }

    @Override
    @RequestMapping(value = "/create_session", method = RequestMethod.POST)
    public Response<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");

            log.info("创建会话 agentId:{} userId:{}", request.getAgentId(), request.getUserId());
            String sessionId = chatService.createSession(request.getAgentId(), request.getUserId());

            CreateSessionResponse responseDTO = new CreateSessionResponse();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponse>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponse>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", request.getAgentId(), request.getUserId(), e);
            return Response.<CreateSessionResponse>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    @RequestMapping(value = "/chat", method = RequestMethod.POST)
    public Response<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            Objects.requireNonNull(request.getMessage(), "用户消息不能为空");
            Objects.requireNonNull(request.getSessionId(), "会话ID不能为空");

            log.info("智能体对话 agentId:{} userId:{}", request.getAgentId(), request.getUserId());
            String sessionId = request.getSessionId();
            if (!StringUtils.isBlank(sessionId)) {
                boolean validated = chatService.validateSession(request.getAgentId(), request.getUserId(), sessionId);
                if (!validated) {
                    log.error("会话验证失败 agentId:{} userId:{} sessionId:{}", request.getAgentId(), request.getUserId(), sessionId);
                    return Response.<ChatResponse>builder()
                            .data(null)
                            .code(ResponseCode.SESSION_NOT_EXIST.getCode())
                            .info(ResponseCode.SESSION_NOT_EXIST.getInfo())
                            .build();
                }
            }

            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(request.getAgentId(), request.getUserId());
            }
            request.setSessionId(sessionId);

            String messages = agentReActServiceCase.chat(request);

            ChatResponse responseDTO = new ChatResponse();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponse>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponse>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", request.getAgentId(), request.getUserId(), e);
            return Response.<ChatResponse>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    @RequestMapping(value = "/chat_stream", method = RequestMethod.POST)
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequest request, HttpServletResponse response) {
        log.info("智能体流式对话 agentId:{} userId:{}", request.getAgentId(), request.getUserId());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            Objects.requireNonNull(request.getMessage(), "用户消息不能为空");

            String sessionId = request.getSessionId();
            if (StringUtils.isNotBlank(sessionId)) {
                boolean validated = chatService.validateSession(request.getAgentId(), request.getUserId(), sessionId);
                if (!validated) {
                    log.error("会话验证失败 agentId: {} userId: {} sessionId: {}", request.getAgentId(), request.getUserId(), sessionId);
                    return errorStream(ResponseCode.SESSION_NOT_EXIST.getInfo());
                }
            } else {
                sessionId = chatService.createSession(request.getAgentId(), request.getUserId());
            }

            request.setSessionId(sessionId);
            return agentReActServiceCase.chatStream(request);
        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            return errorStream(e.getMessage());
        }
    }

    private ResponseBodyEmitter errorStream(String message) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(60_000L);
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.ERROR.getCode());
            event.setContent(message == null ? "流式对话初始化失败" : message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
        return emitter;
    }

    /**
     * 将结构化事件序列化为 SSE/Emitter 两种输出格式，并串行化写入。
     * <p>
     * 嵌套 Agent 和心跳线程都可能写同一个 emitter；同步块避免事件字节交叉或响应已提交时产生竞态。
     */
    private void sendJsonEvent(ResponseBodyEmitter emitter, ObjectMapper objectMapper, Map<String, Object> payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        String eventName = String.valueOf(payload.getOrDefault("event", "message"));

        synchronized (emitter) {
            if (emitter instanceof SseEmitter sseEmitter) {
                sseEmitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(body, MediaType.APPLICATION_JSON));
                return;
            }

            emitter.send(body + "\n");
        }
    }

    /**
     * 首次观察到 invocation 时登记监听器，使该 invocation 后续产生的子事件回流到当前 SSE 请求。
     * registeredInvocationIds 用于去重，防止同一 invocation 在多个事件中重复注册。
     */
    private void registerInvocationListener(
            Event event,
            AgentEventPublisher publisher,
            Set<String> registeredInvocationIds,
            Consumer<AgentEventPublisher.PublishedEvent> listener) {
        if (event == null || listener == null) {
            return;
        }

        String invocationId = event.invocationId();
        if (invocationId == null || invocationId.isBlank() || !registeredInvocationIds.add(invocationId)) {
            return;
        }
        publisher.register(invocationId, listener);
    }

    /**
     * 解析一个 ADK 事件中的工具调用、工具响应和 stateDelta，并转换为前端可消费的 SSE 事件。
     * <p>
     * nested=true 时为子 Agent 事件增加来源标识和展示前缀；各集合负责处理 ADK 重复投递的事件。
     */
    private void processToolEvents(
            ResponseBodyEmitter emitter,
            ObjectMapper objectMapper,
            Event event,
            boolean nested,
            AtomicInteger toolCounter,
            Set<String> emittedToolCalls,
            Set<String> emittedToolResults,
            Set<String> emittedStateKeys,
            Map<String, Deque<String>> pendingToolCalls) throws Exception {
        if (event == null) {
            return;
        }

        String scope = event.invocationId();
        if (scope == null || scope.isBlank()) {
            scope = nested ? "nested" : "root";
        }
        String displayPrefix = nested ? "nested_" + shortInvocationId(scope) + "_" : "";

        for (FunctionCall functionCall : event.functionCalls()) {
            String toolName = functionCall.name().orElse("unknown_tool");
            String rawToolCallId = functionCall.id().orElse(null);
            if (rawToolCallId == null || rawToolCallId.isBlank()) {
                rawToolCallId = "call_" + toolCounter.incrementAndGet();
            }
            String toolCallId = displayPrefix + rawToolCallId;
            if (!emittedToolCalls.add(toolCallId)) {
                continue;
            }

            pendingToolCalls.computeIfAbsent(scope + "|" + toolName, ignored -> new ArrayDeque<>())
                    .addLast(toolCallId);

            Map<String, Object> toolCallEvent = createBaseEvent("tool_call", event);
            decorateNestedEvent(toolCallEvent, event, nested);
            toolCallEvent.put("stage", "tool_running");
            toolCallEvent.put("toolCallId", toolCallId);
            toolCallEvent.put("toolName", toolName);
            toolCallEvent.put("toolArgs", stringifyValue(functionCall.args().orElse(Map.of()), objectMapper));
            toolCallEvent.put("status", "running");
            toolCallEvent.put("content", "正在执行工具：" + toolName);
            toolCallEvent.put("totalToolCalls", Math.max(toolCounter.get(), emittedToolCalls.size()));
            sendJsonEvent(emitter, objectMapper, toolCallEvent);
        }

        for (FunctionResponse functionResponse : event.functionResponses()) {
            String toolName = functionResponse.name().orElse("unknown_tool");
            String rawToolCallId = functionResponse.id().orElse(null);
            if (rawToolCallId != null && rawToolCallId.isBlank()) {
                rawToolCallId = null;
            }
            String queueKey = scope + "|" + toolName;
            Deque<String> toolCallIds = pendingToolCalls.get(queueKey);
            String toolCallId = rawToolCallId == null
                    ? (toolCallIds == null || toolCallIds.isEmpty()
                    ? displayPrefix + "result_" + toolCounter.incrementAndGet()
                    : toolCallIds.removeFirst())
                    : displayPrefix + rawToolCallId;
            String dedupeKey = toolCallId + ":" + toolName;
            if (!emittedToolResults.add(dedupeKey)) {
                continue;
            }

            boolean error = isToolResponseError(functionResponse);
            Map<String, Object> toolResultEvent = createBaseEvent("tool_result", event);
            decorateNestedEvent(toolResultEvent, event, nested);
            toolResultEvent.put("stage", error ? "error" : "thinking");
            toolResultEvent.put("toolCallId", toolCallId);
            toolResultEvent.put("toolName", toolName);
            toolResultEvent.put("content", extractToolResponseContent(functionResponse, objectMapper));
            toolResultEvent.put("status", error ? "error" : "success");
            toolResultEvent.put("totalToolCalls", Math.max(toolCounter.get(), emittedToolCalls.size()));
            sendJsonEvent(emitter, objectMapper, toolResultEvent);
        }

        EventActions actions = event.actions();
        if (actions != null && !actions.stateDelta().isEmpty() && event.functionResponses().isEmpty()) {
            for (Map.Entry<String, Object> entry : actions.stateDelta().entrySet()) {
                String stateKey = scope + "|" + entry.getKey();
                if ("REMOVED".equals(entry.getValue()) || !emittedStateKeys.add(stateKey)) {
                    continue;
                }

                String resultContent = extractStateDeltaContent(entry.getValue(), objectMapper);
                Map<String, Object> stateEvent = createBaseEvent("status", event);
                decorateNestedEvent(stateEvent, event, nested);
                stateEvent.put("stage", "thinking");
                stateEvent.put("content", resultContent);
                stateEvent.put("stateKey", entry.getKey());
                sendJsonEvent(emitter, objectMapper, stateEvent);
            }
        }
    }

    /**
     * 为嵌套 Agent 事件增加前端展示所需的来源元数据，根 Agent 事件保持原有结构。
     */
    private void decorateNestedEvent(Map<String, Object> payload, Event event, boolean nested) {
        if (!nested) {
            return;
        }
        payload.put("nested", true);
        if (event.author() != null && !event.author().isBlank()) {
            payload.put("sourceAgent", event.author());
        }
    }

    /**
     * 截短 invocationId，仅用于嵌套事件的展示前缀，避免把完整内部标识暴露给 UI。
     */
    private String shortInvocationId(String invocationId) {
        return invocationId.length() <= 8 ? invocationId : invocationId.substring(0, 8);
    }

    private SseEmitter createSseEmitter(long timeout) {
        return new SseEmitter(timeout) {
            @Override
            protected void extendResponse(@NotNull ServerHttpResponse outputMessage) {
                super.extendResponse(outputMessage);
                outputMessage.getHeaders().set("Cache-Control", "no-cache, no-transform");
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
                outputMessage.getHeaders().set("Connection", "keep-alive");
            }
        };
    }

    private Map<String, Object> createBaseEvent(String eventName, Event event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventName);
        payload.put("timestamp", System.currentTimeMillis());
        if (event == null) {
            return payload;
        }

        if (event.author() != null && !event.author().isBlank()) {
            payload.put("author", event.author());
        }
        event.branch().ifPresent(branch -> payload.put("branch", branch));
        event.modelVersion().ifPresent(modelVersion -> payload.put("modelVersion", modelVersion));
        event.partial().ifPresent(partial -> payload.put("partial", partial));
        return payload;
    }

    private String extractTextContent(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .stream()
                .flatMap(List::stream)
                .flatMap(part -> part.text().stream())
                .collect(Collectors.joining());
    }

    /**
     * 将 ADK 逐步返回的累计文本转换为增量文本，避免前端重复追加已展示内容。
     */
    private String appendTextDelta(StringBuilder textAccumulator, String eventText) {
        String currentText = textAccumulator.toString();
        if (eventText.equals(currentText)) {
            return "";
        }
        if (eventText.startsWith(currentText)) {
            String textDelta = eventText.substring(currentText.length());
            textAccumulator.append(textDelta);
            return textDelta;
        }
        textAccumulator.append(eventText);
        return eventText;
    }

    private boolean isToolResponseError(FunctionResponse functionResponse) {
        return functionResponse.response()
                .map(response -> (response.containsKey("error") && response.get("error") != null)
                        || Boolean.FALSE.equals(response.get("success")))
                .orElse(false);
    }

    private String extractToolResponseContent(FunctionResponse functionResponse, ObjectMapper objectMapper) {
        Map<String, Object> response = functionResponse.response().orElse(Map.of());
        if (response.isEmpty()) {
            return "";
        }

        Object error = response.get("error");
        if (error != null) {
            return stringifyValue(error, objectMapper);
        }

        Object output = response.getOrDefault("output", response);
        return stringifyValue(output, objectMapper);
    }

    private String stringifyValue(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String extractStateDeltaContent(Object stateValue, ObjectMapper objectMapper) {
        if (stateValue instanceof Map<?, ?> stateMap) {
            Object output = stateMap.containsKey("output") ? stateMap.get("output") : stateValue;
            return stringifyValue(output, objectMapper);
        }

        String resultContent = stateValue != null ? stateValue.toString() : "";
        if (resultContent.startsWith("\"") && resultContent.endsWith("\"") && resultContent.length() > 1) {
            return resultContent.substring(1, resultContent.length() - 1);
        }
        return resultContent;
    }

    private boolean isStateDeltaError(Object stateValue, String resultContent) {
        if (stateValue instanceof Map<?, ?> stateMap) {
            if (Boolean.FALSE.equals(stateMap.get("success"))) {
                return true;
            }
            if (stateMap.get("error") != null) {
                return true;
            }
        }

        if (resultContent == null || resultContent.isBlank()) {
            return false;
        }

        String lowerContent = resultContent.toLowerCase();
        return lowerContent.contains("未绑定 ssh 终端会话")
                || lowerContent.contains("终端会话不存在")
                || lowerContent.contains("已关闭")
                || lowerContent.contains("命令执行异常")
                || lowerContent.contains("danger")
                || lowerContent.contains("error")
                || lowerContent.contains("failed");
    }

}
