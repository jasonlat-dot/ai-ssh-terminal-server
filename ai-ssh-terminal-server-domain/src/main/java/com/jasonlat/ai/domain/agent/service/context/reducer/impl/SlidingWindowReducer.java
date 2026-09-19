package com.jasonlat.ai.domain.agent.service.context.reducer.impl;

import com.jasonlat.ai.domain.agent.service.context.reducer.AbstractReducerSupport;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 滑动窗口裁剪器
 * <p>
 * 功能：保留"最近"的消息——从新到旧逐条装入窗口，
 * 受"窗口条数（20）+ token 预算"双重限制，任一超限即停止。
 * 时效性保障：最近的对话对当前推理最重要。
 * <p>
 * 运行过程：
 * <pre>
 *   messages:  [m0][m1][m2] ... [mN-2][mN-1]   （时间正序，右新左旧）
 *                                   <--------+
 *                                   从尾部向前逐条扫描
 *                     |
 *                     v
 *   每条消息: window.size() >= 20 ?            --是--> 停止
 *             usedTokens + msgTokens > 预算 ?  --是--> 停止
 *                     |否
 *                     v
 *             window.add(0, msg)   从头部插入，保持正序
 *                     |
 *                     v
 *   返回 window（最近 <=20 条且总 token 不超预算的消息）
 * </pre>
 * token 估算：粗略按 content.length()/2（2 个字符约 1 token）。
 *
 */
@Component
public class SlidingWindowReducer extends AbstractReducerSupport {

    private static final int DEFAULT_WINDOW_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }


        List<MessageGroup> window = new ArrayList<>();
        int usedTokens = 0;

        // 从最新消息组向前回收，优先保留最近轮次的完整上下文。
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroup group = groups.get(i);
            int groupTokens = estimateTokens(group);
            if (window.size() >= DEFAULT_WINDOW_SIZE) {
                break;
            }
            if (usedTokens + groupTokens > Math.max(0, tokenBudget)) {
                // 跳过单个超大历史组，继续寻找更早但仍能放入预算的精炼消息。
                continue;
            }
            window.add(0, group);
            usedTokens += groupTokens;
        }

        return window.stream()
                .sorted(Comparator.comparingInt(MessageGroup::getStartIndex))
                .flatMap(group -> group.getMessages().stream())
                .collect(Collectors.toList());

    }

    /**
     * 将原始消息切分为消息组。
     *
     * <p>分组规则与 PriorityReducer 保持一致，保证整个智能体调用链中，
     * 不同裁剪器对“完整上下文单元”的理解一致。
     *
     * <p>案例：
     * <pre>
     *   1. user: 帮我看磁盘空间
     *   2. assistant: tool_calls=[df -h]
     *   3. tool: tool_call_id=call_1, content=...
     *   4. assistant: 磁盘空间正常
     *
     *   切分结果：
     *   G1 = [1]
     *   G2 = [2, 3]
     *   G3 = [4]
     * </pre>
     *
     * <p>注意第 2、3 条虽然是两条消息，但语义上是一件事：
     * “assistant 发起工具调用 + tool 返回执行结果”。
     * 如果把它们拆开裁剪，模型可能只看到“调用了工具”却看不到结果。
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
                List<Map<String, Object>> groupedMessages = new ArrayList<>(4);
                groupedMessages.add(message);

                // assistant 这一条可能一次声明多个 tool_call_id，
                // 所以后续需要把所有属于这次调用批次的 tool result 一起并入同一组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后面的消息仍然是这批 tool_call_id 的结果，就继续吸收到当前组里。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束，后面应开启新分组。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(message), index));
            index++;
        }

        return groups;
    }

    /**
     * 判断一条 assistant 消息是否为工具调用入口消息。
     *
     * @param message 待判断消息
     * @return true 表示该消息包含 tool_calls
     */
    private boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
    }

    /**
     * 判断消息是否存在非空 tool_calls 列表。
     *
     * @param message 待判断消息
     * @return true 表示存在 tool_calls
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 提取 assistant 工具调用消息中的全部 tool_call_id。
     *
     * @param message assistant 工具调用消息
     * @return tool_call_id 集合
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
     * 统计单个消息组的 token 消耗。
     *
     * <p>案例：
     * <pre>
     *   group = [
     *     assistant(tool_calls, content 长度 40),
     *     tool(result, content 长度 200)
     *   ]
     *
     *   groupTokens = 40/2 + 200/2 = 120
     * </pre>
     *
     * @param group 消息组
     * @return 该组总 token 数
     */
    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    /**
     * 消息组。
     *
     * <p>滑动窗口阶段按组保留，保证上下文单元完整。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 该组在原始消息列表中的起始索引。 */
        private int startIndex;
    }


}
