package com.jasonlat.ai.trigger.api;

import com.jasonlat.ai.trigger.api.dto.*;
import com.jasonlat.ai.trigger.api.response.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.security.Principal;

/**
 * 智能体服务接口
 */
public interface IAgentService {

    Response<List<AgentConfigResponse>> queryAiAgentConfigList();

    Response<CreateSessionResponse> createSession(CreateSessionRequest request);

    Response<Boolean> stopChat(SessionDataRequest request);

    ResponseBodyEmitter chatStream(ChatRequest request, HttpServletResponse response, Principal principal);
}
