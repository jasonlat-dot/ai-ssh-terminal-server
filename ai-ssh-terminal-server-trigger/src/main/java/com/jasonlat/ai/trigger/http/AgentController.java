package com.jasonlat.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.IChatService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);
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
                    emitter.send("data: 错误: " + ResponseCode.SESSION_NOT_EXIST.getInfo() + "\n\n");
                    emitter.complete();
                    return emitter;
                }
            }

            if (sessionId.isEmpty()) {
                sessionId = chatService.createSession(request.getAgentId(), request.getUserId());
            }

            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}", request.getAgentId(), request.getUserId(), sessionId, request.getMessage());
            Disposable subscribe = chatService.handleMessageStream(request.getAgentId(), request.getUserId(), sessionId, request.getMessage())
                    .subscribe(
                            event -> {
                                try {
                                    String content = JSON.toJSONString(event.stringifyContent());
                                    if (!content.isEmpty()) {
                                        emitter.send("data: " + content + "\n\n");
                                    }
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                }
                            },
                            emitter::completeWithError,
                            () -> {
                                try {
                                    emitter.send("data: [DONE]\n\n");
                                } catch (Exception e) {
                                    log.error("发送完成标识失败", e);
                                }
                                emitter.complete();
                            }
                    );
            emitter.onCompletion(subscribe::dispose);
        } catch (AppException e) {
            log.error("流式对话异常", e);
            try {
                emitter.send("data: 错误: " + JSON.toJSONString(e.getInfo()) + "\n\n");
            } catch (Exception ex) {
                log.error("发送错误信息失败", ex);
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("流式对话失败", e);
            try {
                emitter.send("data: 错误: "+ JSON.toJSONString(e.getMessage()) + "\n\n");
            } catch (Exception ex) {
                log.error("发送错误信息失败", ex);
            }
            emitter.complete();
        }
        return emitter;
    }
}
