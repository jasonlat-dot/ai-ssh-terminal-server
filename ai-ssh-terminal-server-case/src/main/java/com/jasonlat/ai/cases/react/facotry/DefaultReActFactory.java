package com.jasonlat.ai.cases.react.facotry;

import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.ai.trigger.api.dto.ToolCallDTO;
import com.jasonlat.ai.trigger.api.dto.ToolResultDTO;
import lombok.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 ReAct HTTP 请求的动态上下文。
 *
 * <p>该对象在线程任务开始时创建，请求结束后丢弃，不是跨请求缓存。
 * {@code messageHistory}/{@code recentCommands} 在 RootNode 从 ConversationContextStore 加载，
 * 在 UserFeedbackNode 回写；其他字段只描述当前执行。</p>
 *
 * <p>执行链数据流：
 * <pre>
 * RootNode         → 加载业务历史并初始化请求状态
 * AiCallNode       → 投影 ADK Session，执行 runAsync，观察文本/工具事件
 * ToolCallNode     → 按 toolCallId 核对并归档 ADK 已执行的工具结果
 * LoopDecisionNode → 归一化 stopReason（不再发起外层循环）
 * UserFeedbackNode → 发送最终结果并回写业务历史
 * </pre>
 */
@Component
public class DefaultReActFactory {
    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  会话基本信息
        // ══════════════════════════════════════════════════════════

        /** 对话会话 ID */
        private String chatSessionId;

        /** 用户 ID */
        private String userId;

        /** 智能体 ID */
        private String agentId;

        /** SSH 终端会话 ID */
        private String terminalSessionId;

        /** SSE 事件发射器 */
        private ResponseBodyEmitter emitter;

        // ══════════════════════════════════════════════════════════
        //  业务消息历史
        //  按事件顺序保存 user / assistant / assistant.tool_calls / tool
        // ══════════════════════════════════════════════════════════

        /**
         * 消息历史
         * 格式：{ role: "user"/"assistant"/"tool", content: "...", tool_call_id?: "..." }
         */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /**
         * 当前 invocation 观察到的去重工具调用，用于决定是否进入 ToolCallNode
         */
        @Builder.Default
        private List<ToolCallDTO> currentToolCalls = new ArrayList<>();

        /**
         * 当前 invocation 观察到的去重工具结果，也是最终 DTO 的结果来源
         */
        @Builder.Default
        private List<ToolResultDTO> currentToolResults = new ArrayList<>();

        /**
         * 本次 HTTP 请求中实际观察到的工具调用记录（供最终结果展示）
         */
        @Builder.Default
        private List<ToolCallDTO> executedToolCalls = new ArrayList<>();


        // ══════════════════════════════════════════════════════════
        //  ReAct 循环状态
        // ══════════════════════════════════════════════════════════

        /** Case 层完成的 ADK invocation 数；不是 ADK 内部 LLM/tool 的循环次数。 */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** 最大步数 */
        private int maxSteps;

        /** 单次 ADK invocation 真正允许的 LLM 调用次数。 */
        private int maxLlmCalls;

        /** 最大工具调用次数（总计） */
        private int maxToolCalls;

        /** 每轮最大工具调用次数 */
        private int maxToolCallsPerRound;

        /** 总工具调用次数 */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        /** 当前 invocation 的工具调用次数。 */
        @Builder.Default
        private AtomicInteger roundToolCallCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  AI 响应缓冲（用于累积流式文本）
        // ══════════════════════════════════════════════════════════

        /** 累积的文本响应 */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        /** 累积的 reasoning_content */
        @Builder.Default
        private StringBuilder assistantReasoning = new StringBuilder();

        /** 上一轮次收到的 reasoning_content（需要回传给 API） */
        private String lastReasoningContent;

        // ══════════════════════════════════════════════════════════
        //  中断状态
        // ══════════════════════════════════════════════════════════

        /** 中断原因：user_stop / idle_timeout / max_steps */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        /** SSE 客户端断开或请求被取消。 */
        @Builder.Default
        private AtomicBoolean cancelled = new AtomicBoolean(false);

        /** 正常发送 done 后置为 true，避免 onCompletion 将正常完成误判为断连。 */
        @Builder.Default
        private AtomicBoolean completed = new AtomicBoolean(false);

        // ══════════════════════════════════════════════════════════
        //  结果对象（供 UserFeedbackNode 使用）
        // ══════════════════════════════════════════════════════════

        /** 最终结果 DTO */
        private ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  工具定义
        // ══════════════════════════════════════════════════════════

        /**
         * 工具回调列表（从 ArmoryService 装配链路获取）
         */
        private ToolCallback[] toolCallbacks;

        /**
         * 是否使用 Anthropic 格式（tool_call_id vs tool_use_id）
         */
        private boolean useAnthropicFormat;

        // ══════════════════════════════════════════════════════════
        //  上下文记忆（Phase 1: 动态 Prompt 构建）
        // ══════════════════════════════════════════════════════════

        /** 最近执行的命令记录（用于注入到动态 Prompt 中） */
        @Builder.Default
        private List<String> recentCommands = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void incrementRoundToolCalls() {
            roundToolCallCount.incrementAndGet();
        }

        public void resetRoundToolCalls() {
            roundToolCallCount.set(0);
        }

        public void resetRoundBuffers() {
            // 只重置当前 invocation 的输出，不得清空跨请求加载的 messageHistory/recentCommands。
            currentToolCalls.clear();
            currentToolResults.clear();
            assistantContent.setLength(0);
            assistantReasoning.setLength(0);
        }

        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendAssistantContent(String content) {
            if (content != null && !content.isEmpty()) {
                assistantContent.append(content);
            }
        }

        public void appendToolMessage(String toolCallId, String content) {
            Map<String, Object> msg = useAnthropicFormat
                    ? Map.of("type", "tool_result", "tool_use_id", toolCallId, "content", content)
                    : Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
            messageHistory.add(msg);
        }

        public void addRecentCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            recentCommands.add(command.trim());
            while (recentCommands.size() > 20) {
                recentCommands.remove(0);
            }
        }

    }
}
