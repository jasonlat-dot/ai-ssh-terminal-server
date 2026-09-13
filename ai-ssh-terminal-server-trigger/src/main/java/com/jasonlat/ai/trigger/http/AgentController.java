package com.jasonlat.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl.SshExecuteAdkTool;
import com.jasonlat.ai.trigger.api.IAgentService;
import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import io.reactivex.rxjava3.disposables.Disposable;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
    private ObjectMapper objectMapper;

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
            List<String> messages = chatService.handleMessage(request.getAgentId(), request.getUserId(), sessionId, request.getMessage());

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

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            Objects.requireNonNull(request.getMessage(), "用户消息不能为空");
            Objects.requireNonNull(request.getSessionId(), "会话ID不能为空");

            String sessionId = request.getSessionId();
            if (!StringUtils.isBlank(sessionId)) {
                boolean validated = chatService.validateSession(request.getAgentId(), request.getUserId(), sessionId);
                if (!validated) {
                    log.error("会话验证失败 agentId: {} userId: {} sessionId: {}", request.getAgentId(), request.getUserId(), sessionId);
                    sendEvent(emitter, "error", ResponseCode.SESSION_NOT_EXIST.getInfo());
                    emitter.complete();
                    return emitter;
                }
            }

            if (sessionId.isEmpty()) {
                sessionId = chatService.createSession(request.getAgentId(), request.getUserId());
            }

            String terminalSessionId = request.getTerminalSessionId();
            // 绑定终端会话到 ThreadLocal（核心！工具从这里取 terminalSessionId）
            if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
                SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            }

            /*
             * 直接监听 SshExecuteAdkTool 的真实执行过程。部分 ADK Runner 配置不会把中间
             * FunctionCall/FunctionResponse 继续向外层 Flowable 转发，所以仅检查 Event
             * 无法保证前端一定看到工具。该监听器以 terminalSessionId 隔离并在 SSE 结束
             * 时注销。
             */
            AutoCloseable toolObserver = SshExecuteAdkTool.observeExecutions(
                    terminalSessionId,
                    toolEvent -> {
                        try {
                            sendToolExecutionEvent(emitter, toolEvent);
                        } catch (Exception sendError) {
                            log.warn("发送 SSH 工具事件失败 toolCallId={}", toolEvent.id(), sendError);
                        }
                    }
            );

            String finalSessionId = sessionId;

            /*
             * ADK 的一次对话会产生多个 Event，例如：
             * 1. 模型请求调用工具；
             * 2. 工具返回结果；
             * 3. 模型根据工具结果生成最终回复。
             *
             * 前端希望收到逐步累积的 Markdown 文本，因此这里保存已经发送过的
             * 所有模型文本。每次 text 事件同时携带本次增量 content 和完整 fullText。
             */
            StringBuilder textAccumulator = new StringBuilder();

            /*
             * 保存 RxJava 订阅，浏览器关闭 SSE、发送失败或超时时可以主动 dispose，
             * 避免客户端已经离开后，后端仍继续占用模型及 SSH 会话资源。
             */
            AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

            log.info("流式对话 agentId:{} userId:{} sessionId:{} terminalSessionId:{} message:{}", request.getAgentId(), request.getUserId(), sessionId, request.getTerminalSessionId(), request.getMessage());
            // 在发起 Agent 调用前绑定当前 SSH 终端会话。
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            Disposable subscribe = chatService.handleMessageStream(request.getAgentId(), request.getUserId(), sessionId, request.getMessage(), request.getTerminalSessionId())
                    .subscribe(
                            event -> {
                                try {
                                    /*
                                     * stringifyContent() 只负责取出当前 ADK Event 中可展示的文本。
                                     * 模型返回的 Markdown 保持原样传给前端，由前端 react-markdown 渲染。
                                     */
                                    String eventText = event.stringifyContent();
                                    if (!eventText.isBlank()) {
                                        textAccumulator.append(eventText);

                                        Map<String, Object> textEvent = new HashMap<>();
                                        textEvent.put("event", "text");
                                        textEvent.put("content", eventText);
                                        textEvent.put("fullText", textAccumulator.toString());
                                        sendEvent(emitter, textEvent);
                                        log.info("SSE text 事件已发送: length={}, fullTextLength={}, turnComplete={}",
                                                eventText.length(), textAccumulator.length(), event.turnComplete());
                                    }

                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                    Disposable disposable = subscriptionRef.get();
                                    if (disposable != null) disposable.dispose();
                                    emitter.completeWithError(e);

                                    // 清理 ThreadLocal 和会话级变量
                                    SshExecuteAdkTool.clearCurrentTerminalSession();
                                }
                            },
                            error -> {
                                log.error("MVP 流式对话异常 sessionId={}", finalSessionId, error);
                                try {
                                    sendEvent(emitter, "error", error.getMessage());
                                    emitter.complete();
                                } catch (Exception sendError) {
                                    emitter.completeWithError(sendError);
                                } finally {
                                    closeQuietly(toolObserver);
                                    // 清理 ThreadLocal 和会话级变量
                                    SshExecuteAdkTool.clearCurrentTerminalSession();
                                }
                            },
                            () -> {
                                try {
                                    sendEvent(emitter, "done", textAccumulator.toString());
                                    emitter.complete();
                                    log.info("MVP 流式对话完成 sessionId={}", finalSessionId);
                                } catch (Exception e) {
                                    log.error("发送完成标识失败", e);
                                    emitter.completeWithError(e);
                                } finally {
                                    closeQuietly(toolObserver);
                                    // 清理 ThreadLocal 和会话级变量
                                    SshExecuteAdkTool.clearCurrentTerminalSession();
                                }
                            }
                    );
            subscriptionRef.set(subscribe);
            emitter.onCompletion(() -> {
                subscribe.dispose();
                closeQuietly(toolObserver);
            });
            emitter.onTimeout(() -> {
                subscribe.dispose();
                closeQuietly(toolObserver);
                emitter.complete();
            });
        } catch (Exception e) {
            log.error("MVP 流式对话异常", e);
            try {
                sendEvent(emitter, "error", e.getMessage());
            } catch (Exception ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String event, String content) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", event);
        payload.put("content", content == null ? "" : content);
        sendEvent(emitter, payload);
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) throws Exception {
        emitter.send(SseEmitter.event()
                .name(String.valueOf(payload.get("event")))
                .data(objectMapper.writeValueAsString(payload)));
    }

    /** 把 SSH 工具的真实开始/完成事件转换为前端约定的 SSE 数据。 */
    private void sendToolExecutionEvent(SseEmitter emitter,
                                        SshExecuteAdkTool.ToolExecutionEvent toolEvent) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", toolEvent.completed() ? "tool_result" : "tool_call");
        payload.put("toolCallId", toolEvent.id());
        payload.put("toolName", toolEvent.toolName());
        payload.put("command", toolEvent.command());

        if (toolEvent.completed()) {
            Object output = toolEvent.result().get("output");
            payload.put("content", output == null ? "" : String.valueOf(output));
            payload.put("status", toolEvent.success() ? "success" : "error");
        } else {
            payload.put("arguments", Map.of("command", toolEvent.command()));
            payload.put("status", "running");
        }

        sendEvent(emitter, payload);
        log.info("SSH 工具事件已发送 toolCallId={} completed={} success={}",
                toolEvent.id(), toolEvent.completed(), toolEvent.success());
    }

    /** AutoCloseable 清理允许重复调用，关闭失败只记录日志，不覆盖原始 SSE 结果。 */
    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception closeError) {
            log.debug("清理 SSH 工具事件监听器失败", closeError);
        }
    }
}
