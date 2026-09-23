package com.jasonlat.ai.domain.agent.service.chat;

import com.google.adk.runner.Runner;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.jasonlat.ai.domain.agent.model.entity.ChatCommandEntity;
import com.jasonlat.ai.domain.agent.model.entity.ChatMessageEntity;
import com.jasonlat.ai.domain.agent.model.entity.ChatSessionEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.amory.cache.SessionCache;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

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
    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Override
    public boolean validateSession(String agentId, String userId, String sessionId) {
        // 获取智能体注册信息
        String storeSessionId = sessionCache.get(userId, sessionId, agentId);
        if (StringUtils.isBlank(storeSessionId)) {
            // 兜底处理 - 删除session缓存
            sessionCache.invalidate(agentId, userId, sessionId);
            // 服务重启等原因导致session缓存丢失 重建缓存
            rebuildSession(agentId, userId, sessionId);
        }
        return true;
    }

    private void rebuildSession(String agentId, String userId, String sessionId) {
        // 获取智能体注册信息
        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }
        String appName = aiAgentRegisterVO.getAppName();
        sessionCache.put(agentId, userId, sessionId, aiAgentRegisterVO.getSessionExpireSeconds());
        customAdkSessionService.putSession(appName, userId, null, sessionId);

        // 会话元数据落库：try-catch 旁路写入，DB 写入失败不影响 ADK Session 创建。
        // 旁路原则：如果 DB 异常（如表不存在），只是 DB 里没有这条记录，不影响 Agent 正常运行。
        try {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title("新会话")
                    .messageCount(0)
                    .build());
        } catch (Exception e) {
            log.error("保存会话元数据失败 sessionId={}", sessionId, e);
        }
    }

    @Override
    public String createSession(String agentId, String userId) {
        // 获取智能体注册信息
        AiAgentRegisterVO aiAgentRegisterVO = armoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.CLIENT_A0301.getInfo());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        String sessionId = runner.sessionService().createSession(appName, userId).blockingGet().id();
        sessionCache.put(agentId, userId, sessionId, aiAgentRegisterVO.getSessionExpireSeconds());
        return sessionId;
    }

    @Override
    public String createSession(ChatCommandEntity chatCommandEntity) {
        return createSession(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId());
    }


    /**
     * 查询用户会话列表（2-7 新增）。
     * <p>
     * 为前端历史记录功能提供后端支撑，默认返回最多 20 条。
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数，≤0 时默认 20
     * @return 会话列表
     */
    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        return chatHistoryRepository.querySessionList(agentId, userId, limit > 0 ? limit : 20);
    }

    /**
     * 查询会话消息列表。
     * <p>
     * 为前端历史消息展示提供后端支撑，默认返回最多 100 条。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数，≤0 时默认 100
     * @return 消息列表（时间正序）
     */
    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return chatHistoryRepository.queryMessageList(sessionId, limit > 0 ? limit : 100);
    }

    @Override
    public List<AiAgentConfigTableVO.AgentDefinition> queryAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = agentAutoConfigProperties.getTables();

        if (tables == null || tables.values().isEmpty()) return Collections.emptyList();

        return tables.values().stream()
                .map(AiAgentConfigTableVO::getAgentDefinition)
                .toList();
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
