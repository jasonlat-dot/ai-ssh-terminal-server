package com.jasonlat.ai.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.cloud.aiplatform.v1beta1.Presets;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.model.entity.ChatCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.amory.cache.SessionCache;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Stream;

/**
 * @author jasonlat
 * 2026-04-04  10:46
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory armoryFactory;

    @Resource
    AiAgentAutoConfigProperties agentAutoConfigProperties;

    @Resource
    private SessionCache sessionCache;

    @Override
    public boolean validateSession(String agentId, String userId, String sessionId) {
        // 获取智能体注册信息
        String storeSessionId = sessionCache.get(userId, sessionId, agentId);
        if (StringUtils.isBlank(storeSessionId)) {
            // 兜底处理 - 删除ADK会话
            armoryFactory.deleteAdkSession(agentId, userId, sessionId);
            return false;
        }
        return true;
    }

    @Override
    public String createSession(String agentId, String userId) {
        // 获取智能体注册信息
        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        String sessionId = runner.sessionService().createSession(appName, userId).blockingGet().id();
        sessionCache.put(agentId, userId, sessionId, aiAgentRegisterVO.getSessionExpireSeconds());
        return sessionId;
    }

    @Override
    public String createSession(ChatCommandEntity chatCommandEntity) {
        return createSession(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId());
    }

    @Override
    public List<AiAgentConfigTableVO.AgentDefinition> queryAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = agentAutoConfigProperties.getTables();

        if (tables == null || tables.values().isEmpty()) return Collections.emptyList();

        return tables.values().stream()
                .map(AiAgentConfigTableVO::getAgentDefinition)
                .toList();
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }
        // 创建会话
        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        log.info("智能体非流式对话 agentId:{} userId:{} message: {}", agentId, userId, message);
        ChatCommandEntity chatCommandEntity = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(sessionId)
                .texts(List.of(ChatCommandEntity.Content.Text.builder().message(message).build()))
                .build();
        return handleMessage(chatCommandEntity);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {

        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }
        // 构建 parts
        List<Part> parts = buildParts(chatCommandEntity);
        Content userContent = Content.builder().role("user").parts(parts).build();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> asyncResponseEvents = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), userContent);
        List<String> outputs = new ArrayList<>();
        asyncResponseEvents.blockingForEach(event -> {
            outputs.add(event.stringifyContent());
        });
        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        log.info("智能体流式对话 agentId:{} userId:{} message: {}", agentId, userId, message);
        ChatCommandEntity chatCommandEntity = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(sessionId)
                .texts(List.of(ChatCommandEntity.Content.Text.builder().message(message).build()))
                .build();
        return handleMessageStream(chatCommandEntity);
    }



    @Override
    public Flowable<Event> handleMessageStream(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }
        // 构建 parts
        List<Part> parts = buildParts(chatCommandEntity);
        // 构建 逐字返回 配置
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        RunConfig runConfig = RunConfig.builder()
                .setStreamingMode(RunConfig.StreamingMode.SSE) // 逐字返回
                .setMaxLlmCalls(20)
                .setSaveInputBlobsAsArtifacts(true)
                .build();
        // 构建用户信息
        Content userContent = Content.builder().role("user").parts(parts).build();
        return runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), userContent, runConfig);
    }

    private List<Part> buildParts(ChatCommandEntity chatCommandEntity) {
        List<Part> parts = new ArrayList<>(8);
        // 文本
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }
        // 文件
        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }
        // 内联数据 - 多模态
        List<ChatCommandEntity.Content.InlineData> inlineData = chatCommandEntity.getInlineData();
        if (null != inlineData && !inlineData.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData data : inlineData) {
                parts.add(Part.fromBytes(data.getData(), data.getMimeType()));
            }
        }

        return parts;
    }
}
