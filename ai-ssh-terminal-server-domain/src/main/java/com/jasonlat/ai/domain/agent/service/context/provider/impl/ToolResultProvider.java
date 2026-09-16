package com.jasonlat.ai.domain.agent.service.context.provider.impl;

import com.jasonlat.ai.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具结果上下文提供者（order=40）
 * <p>
 * 功能：按会话缓存 ReAct 循环中的工具执行结果，生成"工具执行摘要"注入 Prompt，
 * 让模型在多轮工具调用后仍能全局回顾"之前执行过什么、结果如何"。
 * <p>
 * 运行过程：
 * <pre>
 *   写入路径（ReAct 每轮工具执行后）：
 *   AiCallNode/ToolCallNode --> ChatContextService.pushToolResult()
 *        |
 *        v
 *   pushResult(sessionId, toolName, result)
 *        |
 *        +--> results[sessionId].add(ToolResultEntry)   追加记录
 *        +--> summaryCache.remove(sessionId)            使摘要缓存失效
 *
 *   读取路径（下一轮构建上下文时）：
 *   provide(sessionId, ...)
 *        |
 *        v
 *   results[sessionId] 为空 ? --> 返回空 Map
 *        |
 *        v
 *   summaryCache.computeIfAbsent(sessionId, generateSummary)  懒摘要
 *        |
 *        +-- 条目 <= 5：逐条拼接 "toolName: 结果(截断100字)"
 *        +-- 条目 >  5："最近执行了 N 个工具调用" + 最近5条(截断80字)
 *        |
 *        v
 *   Map{ toolResultSummary } --> 消息前缀 [工具执行摘要] 段落
 * </pre>
 * 缓存设计：摘要是"懒加载"的——provide 时若缓存命中直接返回；
 * 只有 pushResult 写入新结果才使缓存失效，避免每轮重复生成。
 */
@Component
public class ToolResultProvider implements ContextProvider {

    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "tool-result";
    }

    /**
     * 返回当前 ContextProvider 的执行顺序。
     *
     * <p>
     * ContextProvider 通常会存在多个，例如：
     * </p>
     *
     * <pre>
     * 10 -> SystemInfoProvider
     * 20 -> UserContextProvider
     * 30 -> TerminalContextProvider
     * 40 -> ToolResultProvider
     * </pre>
     *
     * <p>
     * 数字越小通常越早执行，
     * 当前 ToolResultProvider 的优先级为 40。
     * </p>
     *
     * @return 当前 Provider 排序值
     */
    @Override
    public int getOrder() {
        return 40;
    }

    /**
     * 当前 Provider 是否启用。
     *
     * <p>
     * 当前固定返回 true，表示始终启用工具执行结果上下文。
     * </p>
     *
     * <p>
     * 后续也可以改成读取配置：
     * </p>
     *
     * <pre>
     * agent:
     *   context:
     *     tool-result:
     *       enabled: true
     * </pre>
     *
     * 然后动态控制是否启用。
     *
     * @return true 表示启用
     */
    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 采集上下文
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史
     * @return 上下文键值对（如 osInfo、toolResultSummary），允许为空
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        /*
         * 每一个 ContextProvider 都返回自己的上下文 Map。
         * 最终通常由上层 ContextService 将多个 Provider 返回的 Map 合并：
         * {
         *     "osInfo": "...",
         *     "terminalInfo": "...",
         *     "toolResultSummary": "..."
         * }
         */
        Map<String, Object> result = new HashMap<>();
        /*
         * 根据 sessionId 获取当前会话历史工具调用记录。如果该 session 尚未调用过任何工具，
         * results 中不存在对应 Key，此时直接使用 Collections.emptyList()。
         * getOrDefault 不会向 results 中真正写入空 List，仅仅是在 Key 不存在时作为默认返回值。
         */
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        /*
         * 当前会话尚未执行过任何 Tool。没必要向 Prompt 中增加： [工具执行摘要]
         * 这种没有实际内容的上下文，因此直接返回空 Map。
         */
        if (entries.isEmpty()) return result;

        /*
         * 获取当前 session 对应的 Tool 执行摘要。
         * computeIfAbsent 的语义：
         * 1. summaryCache 中已经存在 sessionId -> 直接返回缓存内容
         * 2. summaryCache 中不存在 sessionId
         *      -> 调用 generateSummary(entries)
         *      -> 将计算结果放入 summaryCache
         *      -> 返回计算结果
         */
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries, 5));
        result.put("toolResultSummary", summary);
        return result;
    }

    public void pushResult(String sessionId, String toolName, String result) {
        results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                .add(new ToolResultEntry(toolName, result));
        summaryCache.remove(sessionId);  // 失效摘要缓存
    }

    private String generateSummary(List<ToolResultEntry> entries, int windowIndex) {
        // 少量结果直接拼接，大量结果模板化压缩
        if (entries.size() <= windowIndex) {
            return entries.stream()
                    .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                    .collect(Collectors.joining("\n"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近执行了 ").append(entries.size()).append(" 个工具调用:\n");
        // 只取最近 5 条详细 + 总结
        List<ToolResultEntry> recent = entries.subList(entries.size() - windowIndex, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
                    .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }

}
