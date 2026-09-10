package com.jasonlat.ai.domain.agent.service.amory.factory;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.sessions.BaseSessionService;
import com.jasonlat.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.node.RootNode;
import com.jasonlat.ai.types.utils.BeanUtils;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author jasonlat
 * 2026-03-31  20:52
 */
@Component
public class DefaultArmoryFactory {

    @Resource
    private BeanUtils beanUtils;

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return beanUtils.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 删除 ADK 缓存
     */
    public void deleteAdkSession(String agentId, String userId, String sessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = getAiAgentRegisterVO(agentId);
        BaseSessionService baseSessionService = aiAgentRegisterVO.getRunner().sessionService();
        // 删除 session
        baseSessionService.deleteSession(aiAgentRegisterVO.getAppName(),userId, sessionId).blockingAwait();
    }

    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /** LLM Api */
        private Map<String, OpenAiApi> openAiApiMap = new HashMap<>(8);

        /** LLM chatModel */
        private Map<String, ChatModel> chatModelMap = new HashMap<>(8);

        /** 智能体配置组 */
        private Map<String, BaseAgent> agentGroup = new HashMap<>(8);

        /** 步长 */
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /** 当前处理的智能体工作流节点 */
        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        private Map<String, Object> dataObjects = new HashMap<>(8);

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public List<BaseAgent> queryAgentsByName(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup.keySet().isEmpty()) {
                return Collections.emptyList();
            }
            return agentNames.stream()
                    .map(agentGroup::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }
        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }
    }
}
