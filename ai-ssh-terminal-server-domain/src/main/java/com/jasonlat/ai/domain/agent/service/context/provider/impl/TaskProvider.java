package com.jasonlat.ai.domain.agent.service.context.provider.impl;

import com.jasonlat.ai.domain.agent.model.valobj.enums.ContextPlacement;
import com.jasonlat.ai.domain.agent.model.valobj.intent.TaskStateVO;
import com.jasonlat.ai.domain.agent.service.IIntentService;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import com.jasonlat.ai.domain.agent.service.context.provider.ContextProvider;
import com.jasonlat.ai.domain.agent.service.context.provider.ContextProviderOrder;
import com.jasonlat.ai.domain.agent.service.prompt.PromptEnvelope;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 任务上下文提供者（order=20）
 * <p>
 * 功能：从消息历史中提取首条 user 消息作为"当前任务描述"，
 * 让模型在长对话、多轮工具调用后仍能记住最初的目标（防"任务漂移" - 面试考点）。
 * <p>
 * 运行过程：
 * <pre>
 *   messageHistory（时间正序）
 *   +----------------------------------------------------+
 *   | [0] user:      "帮我排查 nginx 502"   <--+ 初始目标 |
 *   | [1] assistant: "好的，先看日志..."         |          |
 *   | [2] tool:      "tail -100 error.log..."  |          |
 *   | [3] assistant: "发现 upstream 超时..."   |          |
 *   | ...（几十轮后，模型容易忘记最初任务）       |          |
 *   +----------------------------------------------------+
 *                     |
 *                     v  TaskProvider.provide()
 *            从前往后找第一条 role=user 的消息
 *                     |
 *                     v
 *        Map{ taskDescription: "帮我排查 nginx 502" }
 *                     |
 *                     v
 *   DynamicPromptBuilder 渲染为消息前缀的 [当前任务] 段落
 *   --> 每轮对话都提醒模型"你最初的任务是什么"
 * </pre>
 * 设计说明：取"首条"而非"最近"——首条用户消息代表会话的初始目标；
 * 后续 user 消息多为补充/纠偏，已由 MilestoneProvider 覆盖。
 */
@Component
public class TaskProvider implements ContextProvider {

    @Resource
    private ConversationContextStore conversationContextStore;

    @Resource
    private IIntentService intentService;

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public int getOrder() {
        return ContextProviderOrder.TASK;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.STABLE_PREFIX;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // 优先从 TaskStateVO 获取：TaskStateVO 的任务描述是经过意图分类系统"验证"过的，
        // 比从消息历史中推断更准确。分类时如果识别为业务意图，就把当前消息设为任务描述。
        TaskStateVO taskState = intentService.getTaskState(sessionId);
        if (taskState != null && taskState.getTaskDescription() != null && !taskState.getTaskDescription().isBlank()) {
            result.put("taskDescription", taskState.getTaskDescription());
            conversationContextStore.updateCurrentTask(sessionId, taskState.getTaskDescription());
            return result;
        }

        /*
         * 从消息历史中提取本次会话的任务描述。
         * messageHistory 中通常包含多种角色的消息，例如：
         * user      -> 用户输入
         * assistant -> AI 回复
         * tool      -> 工具执行结果
         *
         * 这里只关心用户发送的消息，因此通过 role = user 进行过滤。
         * findFirst() 表示获取消息历史中的第一条用户消息，一般可以将其视为当前 Agent 任务的原始描述 / 初始目标。
         *
         * 如果找到用户消息，则将其 content 保存到 taskDescription，
         * 后续可以作为上下文注入 Prompt，让 Agent 始终知道当前任务最初要解决的问题是什么。
         *
         * 如果 messageHistory 为 null，或者没有找到 user 消息，
         * 则不会向 result 中添加 taskDescription。
         */
        String originalTask = conversationContextStore.getOriginalTask(sessionId);
        if (originalTask != null && !originalTask.isBlank()) {
            result.put("taskDescription", originalTask);
            return result;
        }

        if (messageHistory != null) {
            messageHistory.stream()
                    // 只保留 role = user 的消息
                    .filter(m -> "user".equals(m.get("role")))

                    // 获取第一条用户消息，通常作为本次任务的原始描述
                    .findFirst()

                    // 找到后，将消息内容放入上下文结果中
                    .ifPresent(m -> {
                        String content = (String) m.get("content");
                        if (content != null) {
                            result.put("taskDescription", PromptEnvelope.extractUserMessage(content));
                        }
                    });
        }
        return result;
    }

}
