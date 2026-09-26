package com.jasonlat.ai.trigger.api;

import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.security.Principal;

/**
 * 智能体服务接口
 */
public interface IAgentService {

    Response<Boolean> validateSessionId(SessionDataRequest request);

    Response<List<AgentConfigResponse>> queryAiAgentConfigList();

    Response<CreateSessionResponse> createSession(CreateSessionRequest request);

    Response<List<ChatSessionResponse>> querySessionList(String agentId, String userId, int limit);

    Response<List<ChatMessageResponse>> queryMessageList(String agentId, String userId, String sessionId, int limit);

    Response<Boolean> stopChat(SessionDataRequest request);

    Response<ChatResponse> chat(ChatRequest request, Principal principal);

    ResponseBodyEmitter chatStream(ChatRequest request, HttpServletResponse response, Principal principal);
}
