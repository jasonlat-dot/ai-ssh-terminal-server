package com.jasonlat.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.IChatService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.*;
import java.security.Principal;

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
