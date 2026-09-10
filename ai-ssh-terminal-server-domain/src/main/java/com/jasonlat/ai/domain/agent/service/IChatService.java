package com.jasonlat.ai.domain.agent.service;

import com.google.adk.events.Event;
import com.jasonlat.ai.domain.agent.model.entity.ChatCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.stream.Stream;

public interface IChatService {

    boolean validateSession(String agentId, String userId, String sessionId);

    String createSession(String agentId, String userId);

    String createSession(ChatCommandEntity chatCommandEntity);

    List<AiAgentConfigTableVO.AgentDefinition> queryAgentConfigList();

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    // Event 谷歌的， 返回的流式数据
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    Flowable<Event> handleMessageStream(ChatCommandEntity chatCommandEntity);
}
