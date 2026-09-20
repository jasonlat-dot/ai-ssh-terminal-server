package com.jasonlat.ai.domain.agent.service.context.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级上下文存储。
 * <p>
 * DynamicContext 负责一次 HTTP 请求中的 ReAct 执行状态；
 * ConversationContextStore 负责同一个 sessionId 下跨 HTTP 请求的上下文。
 * </p>
 *
 * <p>主要保存：</p>
 * <ul>
 *     <li>会话最初的任务描述</li>
 *     <li>经过裁剪后的业务消息历史</li>
 *     <li>最近执行的命令</li>
 *     <li>关键里程碑</li>
 *     <li>工具调用结果</li>
 * </ul>
 *
 * <p>
 * 本类只保存可跨请求复用的数据，不保存 ResponseBodyEmitter、当前步骤、
 * 当前轮工具缓冲等请求级对象。缓存的正常生命周期应与 ADK Session 保持一致，
 * 在会话过期、删除或用户新建会话时调用 {@link #clearSession(String)}。
 * </p>
 */
@Component
public class ConversationContextStore {

    /** 最多缓存的会话数量，达到上限后由 Caffeine 自动淘汰低频会话。 */
    private static final int MAX_SESSIONS = 10_000;

    /** 单个会话最多保留的里程碑数量。 */
    private static final int MAX_MILESTONES = 50;

    /** 单个会话最多保留的工具结果数量。 */
    private static final int MAX_TOOL_RESULTS = 50;

    /** 单个会话最多保留的最近命令数量。 */
    private static final int MAX_RECENT_COMMANDS = 20;

    /**
     * sessionId -> 会话状态。
     *
     * 当前没有配置独立的时间过期策略，正常情况下由 SessionCache 在 ADK Session
     * 失效时主动调用 clearSession；maximumSize 负责防止异常情况下无限增长。
     */
    private final Cache<String, SessionContextState> sessions =
            Caffeine.newBuilder()
                    .maximumSize(MAX_SESSIONS)
                    .build();

    /**
     * 初始化并加载会话。
     * 原始任务只在首次调用时写入，后续请求不会用新的用户消息覆盖它。
     * 返回值是防御性快照，调用方修改其中的集合不会直接修改缓存内部状态。
     *
     * @param sessionId          ADK 对话会话 ID
     * @param currentUserMessage 当前请求的用户消息，首次请求时作为原始任务
     * @return 当前会话的不可共享快照
     */
    public ConversationContextSnapshot initializeAndLoad(
            String sessionId,
            String currentUserMessage) {

        SessionContextState state = getOrCreate(sessionId);
        state.initializeOriginalTask(currentUserMessage);
        return state.snapshot();
    }

    /**
     * 将本次 ReAct 执行过程中产生的历史和命令回写到会话状态。
     *
     * @param sessionId      ADK 对话会话 ID
     * @param messageHistory 本次请求结束时的消息历史
     * @param recentCommands 本次请求结束时的最近命令
     */
    public void saveExecutionState(String sessionId, List<Map<String, Object>> messageHistory, List<String> recentCommands) {

        if (isBlank(sessionId)) {
            return;
        }

        SessionContextState state = getOrCreate(sessionId);
        state.replaceExecutionState(messageHistory, recentCommands);
    }

    /**
     * 获取会话首次收到的任务描述。
     *
     * @return 会话不存在时返回 null
     */
    public String getOriginalTask(String sessionId) {
        SessionContextState state = getIfPresent(sessionId);
        return state == null ? null : state.getOriginalTask();
    }

    /** 用户明确切换任务时，更新后续 Prompt 使用的当前任务描述。 */
    public void updateCurrentTask(String sessionId, String taskDescription) {
        if (!isBlank(sessionId) && !isBlank(taskDescription)) {
            getOrCreate(sessionId).updateCurrentTask(taskDescription);
        }
    }

    /** 追加一个会话里程碑，超过上限时淘汰最早的记录。 */
    public void addMilestone(String sessionId, MilestoneVO milestone) {
        if (isBlank(sessionId) || milestone == null) {
            return;
        }

        getOrCreate(sessionId).addMilestone(milestone);
    }

    /**
     * 获取最近的里程碑，结果保持时间正序。
     *
     * @param limit 最大返回数量，非正数返回空列表
     */
    public List<MilestoneVO> getRecentMilestones(String sessionId, int limit) {
        SessionContextState state = getIfPresent(sessionId);
        if (state == null) {
            return Collections.emptyList();
        }

        return state.getRecentMilestones(limit);
    }

    /** 仅清除里程碑，不影响同会话的历史、命令和工具结果。 */
    public void clearMilestones(String sessionId) {
        SessionContextState state = getIfPresent(sessionId);
        if (state != null) {
            state.clearMilestones();
        }
    }

    /** 追加工具执行记录，args 保存调用参数，result 保存执行结果。 */
    public void addToolResult(String sessionId, String toolName, String args, String result) {
        if (isBlank(sessionId)) {
            return;
        }
        getOrCreate(sessionId).addToolResult(new ToolResultEntry(toolName, args, result));
    }

    /**
     * 获取最近的工具执行记录，结果保持执行时间正序。
     *
     * @param limit 最大返回数量，非正数返回空列表
     */
    public List<ToolResultEntry> getRecentToolResults(String sessionId, int limit) {

        SessionContextState state = getIfPresent(sessionId);
        if (state == null) {
            return Collections.emptyList();
        }

        return state.getRecentToolResults(limit);
    }

    public void clearRecentToolResults(String sessionId) {
        SessionContextState state = getIfPresent(sessionId);
        if (state != null) {
            state.clearRecentToolResults();
        }
    }

    /**
     * 清除指定会话的全部业务上下文。
     * ADK Session 过期、删除或者用户开启新会话时调用。
     */
    public void clearSession(String sessionId) {
        if (!isBlank(sessionId)) {
            sessions.invalidate(sessionId);
        }
    }

    /** 获取会话状态；不存在时以原子方式创建，避免并发重复初始化。 */
    private SessionContextState getOrCreate(String sessionId) {
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        return sessions.get(sessionId, key -> new SessionContextState());
    }

    /** 只读取已经存在的会话，不会因为查询操作创建新缓存。 */
    private SessionContextState getIfPresent(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }

        return sessions.getIfPresent(sessionId);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 复制消息列表及每一层消息 Map，避免外部对列表或 Map 的修改污染缓存。
     * Map 中的 value 仍为浅拷贝，当前消息结构主要由不可变字符串组成。
     */
    private static List<Map<String, Object>> copyHistory(
            List<Map<String, Object>> source) {

        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>(source.size());

        for (Map<String, Object> message : source) {
            if (message != null) {
                result.add(new HashMap<>(message));
            }
        }

        return result;
    }

    /** 复制命令列表，避免缓存集合引用泄漏到调用方。 */
    private static List<String> copyCommands(List<String> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        return new ArrayList<>(source);
    }

    /**
     * 提供给一次 ReAct 请求使用的会话快照。
     * 快照与缓存内部集合隔离，可以安全地交给 DynamicContext 修改。
     */
    @Getter
    public static final class ConversationContextSnapshot {

        private final String originalTask;
        private final List<Map<String, Object>> messageHistory;
        private final List<String> recentCommands;

        private ConversationContextSnapshot(
                String originalTask,
                List<Map<String, Object>> messageHistory,
                List<String> recentCommands) {

            this.originalTask = originalTask;
            this.messageHistory = messageHistory;
            this.recentCommands = recentCommands;
        }

        public List<Map<String, Object>> getMessageHistory() {
            return copyHistory(messageHistory);
        }

        public List<String> getRecentCommands() {
            return copyCommands(recentCommands);
        }
    }

    /**
     * 一次工具执行的会话级记录。
     */
    public record ToolResultEntry(String toolName, String args, String result) {

    }

    /**
     * 单个 sessionId 对应的可变状态。
     * Caffeine 只保证缓存容器本身的并发安全，不能保证 value 内部集合安全，
     * 因此本类所有读写方法都使用 synchronized 保护复合操作和容量裁剪。
     */
    private static final class SessionContextState {

        /** 会话第一条有效用户消息，初始化后不再覆盖。 */
        private String originalTask;

        /** 供 TaskProvider 和历史裁剪器使用的业务消息历史。 */
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /** 最近执行的真实命令，不保存命令输出。 */
        private List<String> recentCommands = new ArrayList<>();

        /** 按发现时间正序保存的关键事件。 */
        private final List<MilestoneVO> milestones = new ArrayList<>();

        /** 按执行时间正序保存的工具结果。 */
        private final List<ToolResultEntry> toolResults = new ArrayList<>();

        private synchronized void initializeOriginalTask(String message) {
            if ((originalTask == null || originalTask.isBlank()) && message != null && !message.isBlank()) {

                originalTask = message;
            }
        }

        private synchronized String getOriginalTask() {
            return originalTask;
        }

        private synchronized void updateCurrentTask(String taskDescription) {
            originalTask = taskDescription;
        }

        private synchronized ConversationContextSnapshot snapshot() {
            return new ConversationContextSnapshot(
                    originalTask,
                    copyHistory(messageHistory),
                    copyCommands(recentCommands)
            );
        }

        private synchronized void replaceExecutionState(List<Map<String, Object>> history, List<String> commands) {

            messageHistory = copyHistory(history);
            recentCommands = copyCommands(commands);

            while (recentCommands.size() > MAX_RECENT_COMMANDS) {
                recentCommands.remove(0);
            }
        }

        private synchronized void addMilestone(MilestoneVO milestone) {
            milestones.add(milestone);

            while (milestones.size() > MAX_MILESTONES) {
                milestones.remove(0);
            }
        }

        private synchronized List<MilestoneVO> getRecentMilestones(int limit) {
            if (limit <= 0 || milestones.isEmpty()) {
                return Collections.emptyList();
            }

            int fromIndex = Math.max(0, milestones.size() - limit);
            return new ArrayList<>(milestones.subList(fromIndex, milestones.size()));
        }

        private synchronized void clearMilestones() {
            milestones.clear();
        }

        private synchronized void addToolResult(ToolResultEntry entry) {
            toolResults.add(entry);

            while (toolResults.size() > MAX_TOOL_RESULTS) {
                toolResults.remove(0);
            }
        }

        private synchronized List<ToolResultEntry> getRecentToolResults(int limit) {
            if (limit <= 0 || toolResults.isEmpty()) {
                return Collections.emptyList();
            }

            int fromIndex = Math.max(0, toolResults.size() - limit);
            return new ArrayList<>(toolResults.subList(fromIndex, toolResults.size()));
        }

        private synchronized void clearRecentToolResults() {
            toolResults.clear();
        }
    }
}
