package com.jasonlat.ai.domain.agent.service.context.reducer.impl;

import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.service.context.reducer.AbstractReducerSupport;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.*;

/**
 * 混合裁剪器（ChatContextService 实际使用的裁剪策略）
 * <p>
 * 功能：先压缩超长工具结果，再以 PriorityReducer 的结果为主，使用
 * SlidingWindowReducer 在剩余 token 预算内补充近期消息。
 * <p>
 * 运行过程：
 * <pre>
 *   messages --> 压缩超长 tool content
 *              |
 *              +--> PriorityReducer.reduce()      --> 主保留集合 A（重要性 + 预算）
 *              |
 *              +--> SlidingWindowReducer.reduce() --> 候选集合 B（按时效、按组裁剪）
 *                          |
 *                          v
 *             keep = A + B 中仍能放入剩余预算的完整消息组
 *                          |
 *                          v
 *                   按原顺序输出最终消息
 * </pre>
 */
@Slf4j
@Component
public class HybridReducer extends AbstractReducerSupport {

    @Resource 
    private PriorityReducer priorityReducer;
    
    @Resource 
    private SlidingWindowReducer slidingReducer;

    @Resource
    private AgentContextProperties contextProperties;

    /**
     * 执行混合裁剪。
     *
     * <p>采用“工具结果压缩 + 优先级主导 + 最近窗口预算内补充”的策略：
     * <ul>
     *   <li>PriorityReducer 决定哪些历史消息最值得保留</li>
     *   <li>SlidingWindowReducer 提供近期候选，但不能使最终结果突破预算</li>
     *   <li>工具调用与结果按完整消息组加入，避免只保留半条协议链</li>
     * </ul>
     *
     * @param messages 原始消息列表
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表
     */
    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> workingMessages = compactToolResults(messages);
        log.debug("混合裁剪开始 messageCount={}, tokenBudget={}", workingMessages.size(), tokenBudget);

        List<Map<String, Object>> priorityResult = priorityReducer.reduce(workingMessages, tokenBudget);
        List<Map<String, Object>> slidingResult = slidingReducer.reduce(workingMessages, tokenBudget);

        // PriorityReducer 是主结果，已经完成“最近消息 + 重要性 + 预算”选择。
        Set<Integer> keepIndices = new LinkedHashSet<>(indexSet(priorityResult, workingMessages));

        List<MessageGroup> groups = groupMessages(workingMessages);
        int effectiveBudget = Math.max(0, tokenBudget);
        int usedTokens = estimateSelectedTokens(groups, keepIndices, workingMessages);

        /*
         * SlidingWindow 只负责补充 Priority 未选择的近期消息。按从新到旧整组加入，
         * 每次都重新检查最终预算，避免两个各自满足预算的结果做并集后翻倍。
         */
        Set<Integer> slidingIndices = indexSet(slidingResult, workingMessages);
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroup group = groups.get(i);
            if (group.getIndices().stream().noneMatch(slidingIndices::contains)
                    || keepIndices.containsAll(group.getIndices())) {
                continue;
            }

            int groupTokens = estimateTokens(group, workingMessages);
            if (effectiveBudget == 0 || usedTokens + groupTokens > effectiveBudget) {
                continue;
            }
            keepIndices.addAll(group.getIndices());
            usedTokens += groupTokens;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < workingMessages.size(); i++) {
            if (keepIndices.contains(i)) {
                result.add(workingMessages.get(i));
            }
        }
        log.debug("混合裁剪完成 inputCount={}, outputCount={}, estimatedTokens={}",
                workingMessages.size(), result.size(), estimateMessages(result));
        return result;
    }

    /**
     * 对工具原始输出做防御性截断。保留头尾两端，既能看到命令开头，也能看到
     * 常见的尾部错误/汇总；只复制发生变化的 Map，不修改调用方传入的历史。
     */
    private List<Map<String, Object>> compactToolResults(List<Map<String, Object>> messages) {
        int maxLength = Math.max(0, contextProperties.getReducer().getMaxToolResultCharacters());
        if (maxLength == 0) {
            return new ArrayList<>(messages);
        }

        List<Map<String, Object>> compacted = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            if (!isToolResult(message)) {
                compacted.add(message);
                continue;
            }

            Object contentValue = message.get("content");
            String content = contentValue == null ? "" : String.valueOf(contentValue);
            if (content.length() <= maxLength) {
                compacted.add(message);
                continue;
            }

            String marker = "\n...[工具输出已裁剪]...\n";
            int available = Math.max(0, maxLength - marker.length());
            int headLength = available * 2 / 3;
            int tailLength = available - headLength;
            int tailStart = content.length() - tailLength;
            int errorIndex = findErrorKeywordIndex(content);
            if (tailLength > 0 && errorIndex >= headLength && errorIndex < tailStart) {
                tailStart = Math.max(0, errorIndex - tailLength / 3);
                tailStart = Math.min(tailStart, content.length() - tailLength);
            }
            String truncated = content.substring(0, headLength)
                    + marker.substring(0, Math.min(marker.length(), maxLength))
                    + content.substring(tailStart, tailStart + tailLength);

            Map<String, Object> copy = new LinkedHashMap<>(message);
            copy.put("content", truncated);
            compacted.add(copy);
        }
        return compacted;
    }

    /** 返回首个错误关键词的位置，使长工具结果裁剪后仍尽量保留错误现场。 */
    private int findErrorKeywordIndex(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int firstIndex = -1;
        List<String> keywords = contextProperties.getReducer().getErrorKeywords();
        if (keywords == null) {
            return -1;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            int index = normalized.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (index >= 0 && (firstIndex < 0 || index < firstIndex)) {
                firstIndex = index;
            }
        }
        return firstIndex;
    }

    private boolean isToolResult(Map<String, Object> message) {
        return "tool".equals(stringValue(message.get("role")))
                || "tool_result".equals(stringValue(message.get("type")));
    }

    private int estimateSelectedTokens(
            List<MessageGroup> groups,
            Set<Integer> selectedIndices,
            List<Map<String, Object>> messages) {
        int total = 0;
        for (MessageGroup group : groups) {
            if (group.getIndices().stream().anyMatch(selectedIndices::contains)) {
                total += estimateTokens(group, messages);
            }
        }
        return total;
    }

    private int estimateTokens(MessageGroup group, List<Map<String, Object>> messages) {
        return group.getIndices().stream()
                .map(messages::get)
                .mapToInt(this::estimateToken)
                .sum();
    }

    private int estimateMessages(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(this::estimateToken).sum();
    }

    /**
     * 将子集消息映射回原始列表索引。
     *
     * <p>这里不再使用 indexOf(msg) 的内容匹配方式，而是使用“按引用顺序扫描”的稳定映射：
     * 同一个 Map 实例在原始列表里只会匹配一次，可避免内容相同消息被错误映射到首个位置。
     *
     * <p>案例：
     * <pre>
     *   all 中有两条内容完全一样的消息：
     *   0 -> {role=user, content="继续"}
     *   5 -> {role=user, content="继续"}
     *
     *   如果直接用 indexOf，会永远命中索引 0。
     *   现在改成“按引用 + used[]”扫描后：
     *   - 第一次匹配到 0
     *   - 第二次匹配到 5
     * </pre>
     *
     * <p>这样可以确保合并两个 reducer 结果时，保留的是“原始消息中的正确位置”，
     * 而不是“内容长得像的第一条消息”。
     *
     * @param subset 裁剪结果子集
     * @param all 原始消息全集
     * @return 对应原始消息索引集合
     */
    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new LinkedHashSet<>();
        boolean[] used = new boolean[all.size()];

        for (Map<String, Object> msg : subset) {
            for (int i = 0; i < all.size(); i++) {
                if (!used[i] && all.get(i) == msg) {
                    indices.add(i);
                    used[i] = true;
                    break;
                }
            }
        }
        return indices;
    }

    /**
     * 将原始消息切分为消息组。
     *
     * <p>分组规则与其他 reducer 保持一致，确保在整个智能体调用链中，
     * “完整上下文单元”的定义一致，不会在不同 reducer 之间出现理解偏差。
     *
     * <p>案例：
     * <pre>
     *   1. user: 查看磁盘
     *   2. assistant: tool_calls=[call_1]
     *   3. tool: tool_call_id=call_1, content="磁盘 80%"
     *   4. assistant: 建议清理日志
     *
     *   分组结果：
     *   G1 = [0]
     *   G2 = [1, 2]
     *   G3 = [3]
     * </pre>
     *
     * <p>HybridReducer 这里只记录索引，不直接保存消息内容，
     * 因为它最终关心的是“哪些原始位置需要保留”，方便在两个 reducer 结果合并后统一恢复顺序。
     *
     * @param messages 原始消息列表
     * @return 消息组列表
     */
    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                List<Integer> indices = new ArrayList<>();
                indices.add(index);

                // assistant 声明的这批 tool_call_id，决定后面哪些 tool result 应该并入同组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        indices.add(next);
                        next++;
                        continue;
                    }
                    break;
                }

                groups.add(new MessageGroup(indices, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(index), index));
            index++;
        }

        return groups;
    }

    /**
     * 判断一条 assistant 消息是否为工具调用入口消息。
     *
     * <p>案例：
     * <pre>
     *   messageA = {role=assistant, tool_calls=[{id=call_1, type=...}]}
     *              -> true  （assistant 且有 tool_calls，是工具调用入口）
     *
     *   messageB = {role=assistant, content="根据磁盘情况，建议清理日志"}
     *              -> false （assistant 但没有 tool_calls，是普通回复）
     *
     *   messageC = {role=tool, tool_call_id=call_1, content="磁盘 80%"}
     *              -> false （role 不是 assistant）
     * </pre>
     *
     * @param message 待判断消息
     * @return true 表示该消息包含 tool_calls
     */
    private boolean isToolCallAssistant(Map<String, Object> message) {
        String role = stringValue(message.get("role"));
        return ("assistant".equals(role) || "model".equals(role)) && hasToolCalls(message);
    }

    /**
     * 判断消息是否存在非空 tool_calls 列表。
     *
     * <p>案例：
     * <pre>
     *   messageA = {role=assistant, tool_calls=[{id=call_1, type="bash"}]}  -> true
     *   messageB = {role=assistant, content="直接回复，无工具调用"}             -> false（无 tool_calls 字段）
     *   messageC = {role=assistant, tool_calls=[]}                          -> false（tool_calls 为空列表）
     *   messageD = {role=tool, content="结果"}                               -> false（role 不是 assistant）
     * </pre>
     *
     * @param message 待判断消息
     * @return true 表示消息中存在非空 tool_calls 列表
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 提取 assistant 工具调用消息中的全部 tool_call_id。
     *
     * <p>案例：
     * <pre>
     *   message = {role=assistant, tool_calls=[
     *                {id="call_1", function={name="bash"}},
     *                {id="call_2", function={name="read_file"}}
     *              ]}
     *   -> {"call_1", "call_2"}
     *
     *   message = {role=assistant, tool_calls=[]}
     *   -> {} （空列表，返回空集合）
     *
     *   message = {role=user, content="hello"}
     *   -> {} （无 tool_calls 字段，返回空集合）
     * </pre>
     *
     * @param message assistant 工具调用消息
     * @return tool_call_id 集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new LinkedHashSet<>();
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
     * 消息组。
     *
     * <p>HybridReducer 不直接关心组内消息内容，只关心组覆盖的原始索引范围，
     * 以便在最终合并阶段稳定地保留完整上下文单元。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 该消息组覆盖的原始消息索引列表。 */
        private List<Integer> indices;
        /** 该组在原始消息列表中的起始位置。 */
        private int startIndex;
    }

}
