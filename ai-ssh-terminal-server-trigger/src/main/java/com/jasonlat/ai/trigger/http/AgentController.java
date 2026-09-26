package com.jasonlat.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.cases.react.multimodal.ChatRequestContentSupport;
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
import java.security.Principal;
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

    /** 历史会话按最近活动时间倒序返回。 */
    @Override
    @GetMapping("/query_session_list")
    public Response<List<ChatSessionResponse>> querySessionList(
            @RequestParam("agentId") String agentId, @RequestParam("userId") String userId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            if (StringUtils.isAnyBlank(agentId, userId)) {
                return Response.error("智能体 ID 和用户 ID 不能为空");
            }
            int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
            List<ChatSessionResponse> sessions = chatService.querySessionList(agentId, userId, safeLimit)
                    .stream()
                    .map(session -> new ChatSessionResponse(session.getId(), session.getTitle(),
                            session.getMessageCount(), session.getCreatedAt(), session.getUpdatedAt()))
                    .toList();
            return Response.success(ResponseCode.SUCCESS.getInfo(), sessions);
        } catch (Exception exception) {
            log.error("查询会话列表失败 agentId:{} userId:{}", agentId, userId, exception);
            return Response.error(ResponseCode.UN_ERROR.getInfo());
        }
    }

    /** 读取消息前校验 sessionId 是否确实属于该用户和 Agent。 */
    @Override
    @GetMapping("/query_message_list")
    public Response<List<ChatMessageResponse>> queryMessageList(
            @RequestParam("agentId") String agentId, @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            if (StringUtils.isAnyBlank(agentId, userId, sessionId)
                    || !chatService.ownsSession(agentId, userId, sessionId)) {
                return Response.<List<ChatMessageResponse>>builder()
                        .code(ResponseCode.SESSION_NOT_EXIST.getCode())
                        .info(ResponseCode.SESSION_NOT_EXIST.getInfo())
                        .build();
            }
            int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
            List<ChatMessageResponse> messages = chatService.queryMessageList(sessionId, safeLimit)
                    .stream()
                    .map(message -> new ChatMessageResponse(message.getId(), message.getRole(),
                            message.getContent(), message.getToolName(), message.getToolCallId(),
                            message.getCreatedAt()))
                    .toList();
            return Response.success(ResponseCode.SUCCESS.getInfo(), messages);
        } catch (Exception exception) {
            log.error("查询会话消息失败 sessionId:{} userId:{}", sessionId, userId, exception);
            return Response.error(ResponseCode.UN_ERROR.getInfo());
        }
    }

    /** 主动停止正在运行的流式请求；执行层按 agentId/userId/sessionId 三者匹配。 */
    @Override
    @PostMapping("/stop_chat")
    public Response<Boolean> stopChat(@RequestBody SessionDataRequest request) {
        if (request == null || StringUtils.isAnyBlank(request.getAgentId(), request.getUserId(), request.getSessionId())) {
            return Response.error("智能体 ID、用户 ID 和会话 ID 不能为空");
        }
        try {
            boolean stopped = agentReActServiceCase.stopChat(
                    request.getAgentId(), request.getUserId(), request.getSessionId());
            log.info("主动停止对话 | agentId:{} | userId:{} | sessionId:{} | stopped:{}",
                    request.getAgentId(), request.getUserId(), request.getSessionId(), stopped);
            return Response.success(ResponseCode.SUCCESS.getInfo(), stopped);
        } catch (Exception exception) {
            log.error("主动停止对话失败 sessionId:{}", request.getSessionId(), exception);
            return Response.error(ResponseCode.UN_ERROR.getInfo());
        }
    }

    @Override
    @RequestMapping(value = "/chat", method = RequestMethod.POST)
    public Response<ChatResponse> chat(@RequestBody ChatRequest request, Principal principal) {
        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            ChatRequestContentSupport.validateAndNormalize(request);
            request.setAuthenticatedUserId(principal == null ? null : principal.getName());
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
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequest request, HttpServletResponse response, Principal principal) {
        log.info("智能体流式对话 agentId:{} userId:{}", request.getAgentId(), request.getUserId());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        try {
            Objects.requireNonNull(request.getAgentId(), "智能体ID不能为空");
            Objects.requireNonNull(request.getUserId(), "用户ID不能为空");
            ChatRequestContentSupport.validateAndNormalize(request);
            // 在切换到异步线程前提取可信身份，不使用前端传入的 userId 校验附件所有权。
            request.setAuthenticatedUserId(principal == null ? null : principal.getName());

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
//            throw new IllegalArgumentException("这是测试报错");
            return agentReActServiceCase.chatStream(request);
        } catch (AppException e) {
            return errorStream(e.getInfo(), e.getCode());
        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            return errorStream(e.getMessage());
        }
    }

    private ResponseBodyEmitter errorStream(String message) {
        return errorStream(message, null);
    }

    private ResponseBodyEmitter errorStream(String message, String code) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(60_000L);
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.ERROR.getCode());
            event.setCode(code);
            event.setContent(message == null ? "流式对话初始化失败" : message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
        return emitter;
    }

}
