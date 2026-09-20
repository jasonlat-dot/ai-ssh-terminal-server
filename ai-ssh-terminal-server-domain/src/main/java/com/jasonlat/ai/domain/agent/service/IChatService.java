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

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    // Event 谷歌的， 返回的流式数据
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    Flowable<Event> handleMessageStream(ChatCommandEntity chatCommandEntity);

    /**
     * 查询用户会话列表
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 查询会话消息列表
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);


    /**
     * 处理消息（流式）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     * @param terminalSessionId SSH终端会话ID（用于MCP工具调用）
     * @return 事件流
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId);

}
