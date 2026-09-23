package com.jasonlat.ai.domain.agent.service;

import com.google.adk.events.Event;
import com.jasonlat.ai.domain.agent.model.entity.ChatCommandEntity;
import com.jasonlat.ai.domain.agent.model.entity.ChatMessageEntity;
import com.jasonlat.ai.domain.agent.model.entity.ChatSessionEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.stream.Stream;

public interface IChatService {

    boolean validateSession(String agentId, String userId, String sessionId);

    String createSession(String agentId, String userId);

    String createSession(ChatCommandEntity chatCommandEntity);

    List<AiAgentConfigTableVO.AgentDefinition> queryAgentConfigList();

    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    boolean ownsSession(String agentId, String userId, String sessionId);

    /**
     * 查询会话消息列表
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);

}
