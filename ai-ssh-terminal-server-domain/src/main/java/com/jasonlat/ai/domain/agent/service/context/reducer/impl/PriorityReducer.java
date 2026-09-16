package com.jasonlat.ai.domain.agent.service.context.reducer.impl;

import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.service.context.reducer.AbstractReducerSupport;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于消息优先级和时间位置的上下文裁剪器。
 *
 * <p>裁剪规则：</p>
 * <ol>
 *     <li>最近 N 条消息无条件保留，N 来自 minimum-recent-messages。</li>
 *     <li>剩余消息按照优先级从高到低选择。</li>
 *     <li>相同优先级下，优先保留较新的消息。</li>
 *     <li>选择完成后恢复消息原始时间顺序。</li>
 * </ol>
 *
 * <p>优先级：</p>
 * <ul>
 *     <li>CRITICAL：工具执行错误</li>
 *     <li>HIGH：系统消息、包含配置文件或路径的用户消息</li>
 *     <li>MEDIUM：普通消息</li>
 *     <li>LOW：过长的模型回复</li>
 * </ul>
 */
@Component
public class PriorityReducer extends AbstractReducerSupport {

    private final AgentContextProperties.Reducer properties;

    public PriorityReducer(AgentContextProperties contextProperties) {
        this.properties = contextProperties.getReducer();
    }

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int effectiveBudget = Math.max(tokenBudget, 0);

        List<PrioritizedMessage> prioritizedMessages = buildPrioritizedMessages(messages);

        if (prioritizedMessages.isEmpty()) {
            return List.of();
        }

        /*
         * 保存被选中的原始消息下标。
         * 使用下标而不是 Map.equals/indexOf，避免内容相同的消息定位错误。
         */
        Set<Integer> selectedIndexes = new HashSet<>();
        int usedTokens = 0;
        /*
         * 第一步：保留最近 N 条消息。
         *
         * 最新的用户输入和模型回复是继续当前对话的必要条件，因此即使它们本身
         * 超出 tokenBudget，也不会直接删除。超长消息应当在进入裁剪器之前做摘要。
         */
        int minimumRecentMessages = Math.max(0, properties.getMinimumRecentMessages());
        int recentStart = Math.max(0, prioritizedMessages.size() - minimumRecentMessages);

        for (int i = recentStart; i < prioritizedMessages.size(); i++) {
            PrioritizedMessage recentMessage = prioritizedMessages.get(i);
            if (selectedIndexes.add(recentMessage.index())) {
                usedTokens += recentMessage.estimatedTokens();
            }
        }

        /*
         * 第二步：取出剩余候选消息。
         */
        List<PrioritizedMessage> candidates = new ArrayList<>();

        for (PrioritizedMessage prioritizedMessage : prioritizedMessages) {
            if (!selectedIndexes.contains(prioritizedMessage.index())) {
                candidates.add(prioritizedMessage);
            }
        }

        /*
         * 第三步：按照优先级选择。
         *
         * 优先级高的在前；
         * 优先级相同时，下标大的消息更新，因此优先保留。
         */
        candidates.sort(
                Comparator
                        .comparingInt(
                                (PrioritizedMessage message) ->
                                        message.priority().getWeight()
                        )
                        .reversed()
                        .thenComparing(
                                Comparator.comparingInt(
                                        PrioritizedMessage::index
                                ).reversed()
                        )
        );

        /*
         * 第四步：依次把候选消息放入剩余 token 预算。
         */
        for (PrioritizedMessage candidate : candidates) {
            int candidateTokens = candidate.estimatedTokens();
            if (usedTokens + candidateTokens > effectiveBudget) {
                continue;
            }
            if (selectedIndexes.add(candidate.index())) {
                usedTokens += candidateTokens;
            }
        }

        /*
         * 第五步：恢复原始时间顺序。
         *
         * 模型看到的历史必须是旧消息在前、新消息在后，不能按照优先级顺序发送。
         */
        List<Map<String, Object>> result = new ArrayList<>();
        for (PrioritizedMessage prioritizedMessage : prioritizedMessages) {
            if (selectedIndexes.contains(prioritizedMessage.index())) {
                result.add(prioritizedMessage.message());
            }
        }
        return result;
    }

    /**
     * 为消息增加原始下标、优先级及预估 token 数量。
     */
    private List<PrioritizedMessage> buildPrioritizedMessages(List<Map<String, Object>> messages) {
        List<PrioritizedMessage> result = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            if (message == null) {
                continue;
            }
            result.add(new PrioritizedMessage(index, message, inferPriority(message), estimateToken(message)));
        }
        return result;
    }

    /**
     * 根据角色和消息内容推断优先级。
     */
    private MessagePriority inferPriority(Map<String, Object> message) {
        String role = Objects.toString(message.get("role"), "");
        String content = Objects.toString(message.get("content"), "");

        if ("tool".equals(role) && containsAny(content, properties.getErrorKeywords())) {
            return MessagePriority.CRITICAL;
        }

        if ("system".equals(role)) {
            return MessagePriority.HIGH;
        }

        if ("user".equals(role) && containsAny(content, properties.getImportantPathSuffixes())) {
            return MessagePriority.HIGH;
        }

        int longAssistantThreshold = Math.max(0, properties.getLongAssistantThreshold());
        if (("assistant".equals(role) || "model".equals(role))
                && content.length() > longAssistantThreshold) {
            return MessagePriority.LOW;
        }

        return MessagePriority.MEDIUM;
    }

    private boolean containsAny(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null
                    && !keyword.isBlank()
                    && normalizedContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @Getter
    private enum MessagePriority {
        CRITICAL(400),
        HIGH(300),
        MEDIUM(200),
        LOW(100);

        private final int weight;

        MessagePriority(int weight) {
            this.weight = weight;
        }

    }

    /**
     * @param index 消息在原始列表中的位置。
     */
    private record PrioritizedMessage(int index, Map<String, Object> message,
                                      MessagePriority priority, int estimatedTokens) {

    }
}
