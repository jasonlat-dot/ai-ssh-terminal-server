package com.jasonlat.ai.domain.agent.service.context.reducer.impl;

import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.service.context.reducer.AbstractReducerSupport;
import com.jasonlat.ai.domain.agent.service.util.AgentUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
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
import java.util.stream.Collectors;

/**
 * 基于消息优先级和时间位置的上下文裁剪器。
 *
 * <p>裁剪规则：</p>
 * <ol>
 *     <li>当前最新消息始终保留，并在预算内优先补齐最近 N 个消息组。</li>
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
 *     <li>LOW：成功工具原文、过长的模型回复</li>
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
        // 空输入直接返回空列表，避免后续 subList / 排序逻辑做无意义处理。
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 先将消息归并成“可裁剪单元”，普通消息单独成组，工具调用链按组处理。
        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        int effectiveBudget = Math.max(0, tokenBudget);
        int preferredRecentCount = Math.min(
                Math.max(1, properties.getMinimumRecentMessages()),
                groups.size()
        );

        /*
         * 最近消息是优先项而不是无条件突破预算项。最后一组（通常是当前 user
         * 消息）始终保留；再向前补齐配置数量。某个历史组过大时跳过它，继续
         * 尝试更早但更精炼的消息，避免一段原始工具输出挡住 assistant 总结。
         * tokenBudget <= 0 时保留旧语义：只返回配置要求的最近消息组。
         */
        List<MessageGroup> kept = new ArrayList<>();
        Set<Integer> keptStartIndices = new HashSet<>();
        int usedTokens = 0;
        for (int i = groups.size() - 1; i >= 0 && kept.size() < preferredRecentCount; i--) {
            MessageGroup group = groups.get(i);
            int groupTokens = estimateTokens(group);
            boolean latestGroup = i == groups.size() - 1;
            boolean fitsBudget = effectiveBudget == 0 || usedTokens + groupTokens <= effectiveBudget;
            if (!latestGroup && !fitsBudget) {
                continue;
            }
            kept.add(group);
            keptStartIndices.add(group.getStartIndex());
            usedTokens += groupTokens;
        }

        // 未进入最近窗口的所有消息组继续参与优先级竞争。
        List<MessageGroup> candidates = groups.stream()
                .filter(group -> !keptStartIndices.contains(group.getStartIndex()))
                .sorted(Comparator
                        // 高优先级优先保留，例如错误结果、关键 system/user 指令、tool_calls。
                        .comparing((MessageGroup g) -> g.getPriority().weight()).reversed()
                        // 同优先级时，越新的历史组越优先保留，尽量让上下文更贴近当前问题。
                        .thenComparing(MessageGroup::getStartIndex, Comparator.reverseOrder())).collect(Collectors.toCollection(ArrayList::new));

        // 按排序后的优先级依次尝试回填，只要还没超预算就整组加入。
        for (MessageGroup group : candidates) {
            int groupTokens = estimateTokens(group);
            if (effectiveBudget > 0 && usedTokens + groupTokens <= effectiveBudget) {
                kept.add(group);
                usedTokens += groupTokens;
            }
        }

        // 虽然回填阶段按优先级选组，但最终输出必须恢复原始时间顺序。
        kept.sort(Comparator.comparing(MessageGroup::getStartIndex));
        return kept.stream()
                .flatMap(group -> group.getMessages().stream())
                .collect(Collectors.toList());
    }

    /**
     * 将原始消息切分为消息组。
     *
     * <p>规则：
     * <ul>
     *   <li>普通 user / system / assistant 消息：单条成组</li>
     *   <li>assistant 中包含 tool_calls：与其后连续匹配的 tool result 合并成组</li>
     * </ul>
     *
     * <p>这样做的目的，是把工具调用请求与结果视作一个原子上下文单元，
     * 裁剪时只能整组保留或整组删除，不能留下半截历史。
     *
     * <p>案例：
     * <pre>
     *   1. assistant: tool_calls=[call_1]
     *   2. tool: tool_call_id=call_1, content="permission denied"
     *   3. assistant: 我执行失败了
     *
     *   分组后：
     *   G1 = [1, 2]
     *   G2 = [3]
     * </pre>
     *
     * <p>这样即便预算吃紧，G1 也会作为“完整失败链路”一起保留或一起删除，
     * 不会出现只留下第 1 条调用请求、却把第 2 条错误结果裁掉的情况。
     *
     * @param messages 原始消息列表
     * @return 切分后的消息组列表
     */
    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                // assistant 发起工具调用时，先把当前 assistant 消息加入组头。
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                // 收集本次 assistant 工具调用中声明的全部 tool_call_id。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后续消息还是当前 tool_call_id 对应的 tool result，就继续并入同一组。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, inferPriority(groupedMessages), index));
                index = next;
                continue;
            }

            // 补充：兼容当前 ADK 自动执行分支缺失 assistant tool_calls 的情况，把孤立的 tool result 也单独成组
            // 避免 tool result 和下一个 assistant / user 合并导致越界
            groups.add(new MessageGroup(List.of(message), inferPriority(List.of(message)), index));
            index++;
        }

        return groups;
    }

    /**
     * 计算一个消息组的优先级。
     *
     * <p>当前策略取组内“最高优先级”作为整组优先级，原因是：
     * 只要组里包含一条关键消息（如错误 tool result），整组都应该被优先保留。
     *
     * <p>案例：
     * <pre>
     *   group = [
     *     assistant(tool_calls),          -> HIGH
     *     tool("permission denied")       -> CRITICAL
     *   ]
     *
     *   最终组优先级 = CRITICAL
     * </pre>
     *
     * @param messages 消息组内的消息列表
     * @return 该消息组的优先级
     */
    private MessagePriority inferPriority(List<Map<String, Object>> messages) {
        return messages.stream()
                .map(this::inferPriority)
                .max(Comparator.comparingInt(MessagePriority::weight))
                .orElse(MessagePriority.MEDIUM);
    }

    /**
     * 根据角色和消息内容推断优先级。
     */
    private MessagePriority inferPriority(Map<String, Object> message) {
        String role = Objects.toString(message.get("role"), "");
        String content = Objects.toString(message.get("content"), "");

        boolean toolResult = "tool".equals(role)
                || "tool_result".equals(stringValue(message.get("type")));

        // 只有真正命中错误关键词的工具结果才提升为 CRITICAL。
        if (toolResult && containsAny(content, properties.getErrorKeywords())) {
            return MessagePriority.CRITICAL;
        }

        // 成功工具原文通常已有 assistant 总结，优先级低于对话文本。
        if (toolResult) {
            return MessagePriority.LOW;
        }

        // assistant 里出现 tool_calls，说明这是调用工具的入口消息，和 tool result 配对价值很高。
        if (AgentUtils.isAssistant(role) && hasToolCalls(message)) {
            return MessagePriority.HIGH;
        }

        // system 指令默认高优先级，避免基础行为约束被裁掉。
        if ("system".equals(role)) {
            return MessagePriority.HIGH;
        }

        // 用户显式给出路径或配置文件时，通常是关键操作目标，不应轻易裁掉。
        if ("user".equals(role) && containsAny(content, properties.getImportantPathSuffixes())) {
            return MessagePriority.HIGH;
        }

        // 超长 assistant 文本通常是冗长说明，优先级可降低。
        int longAssistantThreshold = Math.max(0, properties.getLongAssistantThreshold());
        if (AgentUtils.isAssistant(role) && content.length() > longAssistantThreshold) {
            return MessagePriority.LOW;
        }

        return MessagePriority.MEDIUM;
    }

    /**
     * 判断一条 assistant 消息是否为“工具调用入口消息”。
     *
     * @param message 待判断消息
     * @return true 表示该 assistant 消息包含 tool_calls
     */
    private boolean isToolCallAssistant(Map<String, Object> message) {
        String role = stringValue(message.get("role"));
        return ("assistant".equals(role) || "model".equals(role)) && hasToolCalls(message);
    }

    /**
     * 判断消息中是否包含 tool_calls 字段。
     *
     * @param message 待判断消息
     * @return true 表示存在非空 tool_calls 列表
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 从 assistant 的 tool_calls 中提取全部调用 ID。
     *
     * <p>一个 assistant 消息可能一次发起多个工具调用，因此这里返回 Set，
     * 供后续 tool result 匹配阶段统一判断。
     *
     * <p>案例：
     * <pre>
     *   tool_calls = [
     *     {id=call_1, name="ls"},
     *     {id=call_2, name="tail"}
     *   ]
     *
     *   提取结果 = {"call_1", "call_2"}
     * </pre>
     *
     * @param message assistant 工具调用消息
     * @return 当前消息声明的全部 tool_call_id
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new HashSet<>();
        Object toolCalls = message.get("tool_calls");
        if (!(toolCalls instanceof List<?> list)) {
            return toolCallIds;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> call) {
                Object id = ((Map<String, Object>) call).get("id");
                if (id != null) {
                    toolCallIds.add(String.valueOf(id));
                }
            }
        }
        return toolCallIds;
    }

    /**
     * 判断一条消息是否是当前工具调用组对应的 tool result。
     * 兼容 OpenAI (role=tool, tool_call_id) 和 Anthropic (type=tool_result, tool_use_id)
     *
     * <p>案例：
     * <pre>
     *   当前组 toolCallIds = {"call_1", "call_2"}
     *
     *   messageA = {role=tool, tool_call_id=call_1}      -> true
     *   messageB = {type=tool_result, tool_use_id=call_2} -> true
     *   messageC = {role=assistant, content="继续分析"}     -> false
     * </pre>
     *
     * @param message 待匹配消息
     * @param toolCallIds 当前工具调用组持有的 tool_call_id 集合
     * @return true 表示该消息属于当前工具调用组
     */
    private boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        String role = stringValue(message.get("role"));
        String type = stringValue(message.get("type"));

        if (!"tool".equals(role) && !"tool_result".equals(type)) {
            return false;
        }

        String toolCallId = stringValue(message.get("tool_call_id"));
        if (toolCallId.isEmpty()) {
            toolCallId = stringValue(message.get("tool_use_id"));
        }

        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    /**
     * 判断文本是否包含任一关键字。
     *
     * @param content 待匹配文本
     * @param keywords 关键字列表
     * @return true 表示命中任一关键字
     */
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

    /**
     * 统计单个消息组的 token 消耗。
     *
     * <p>案例：
     * <pre>
     *   G4 = [assistant(tool_calls, 40 chars), tool(error, 120 chars)]
     *   groupTokens = 40/2 + 120/2 = 80
     * </pre>
     *
     * @param group 消息组
     * @return 该组总 token 数
     */
    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    /**
     * 统计多个消息组的总 token 消耗。
     *
     * <p>案例：
     * <pre>
     *   kept = [G4, G5]
     *   estimateTokens(kept) = estimateTokens(G4) + estimateTokens(G5)
     * </pre>
     *
     * @param groups 消息组列表
     * @return 总 token 数
     */
    private int estimateTokens(List<MessageGroup> groups) {
        return groups.stream().mapToInt(this::estimateTokens).sum();
    }

    /**
     * 安全获取对象字符串值，避免 null 参与后续判断。
     *
     * @param value 原始对象
     * @return 非 null 字符串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Getter
    private enum MessagePriority {
        CRITICAL(100),
        HIGH(80),
        MEDIUM(50),
        LOW(20);

        private final int weight;

        MessagePriority(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }

    }

    /**
     * 消息组。
     *
     * <p>将强相关的一组消息（如工具调用及其结果）绑定在一起，
     * 保证在裁剪和回填时同进同出，防止上下文断裂。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 组优先级，通常取组内消息最高优先级。 */
        private MessagePriority priority;
        /** 该组在原始消息列表中的起始索引，用于最终恢复时间顺序。 */
        private int startIndex;
    }
}
