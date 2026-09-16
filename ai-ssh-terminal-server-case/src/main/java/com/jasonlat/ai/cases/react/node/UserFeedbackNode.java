package com.jasonlat.ai.cases.react.node;

import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * ReAct 用户反馈节点（结果发送 + 清理）
 *
 * <p>职责：
 * 1. 构建最终 ReActResultDTO
 * 2. 发送 done SSE 事件
 * 3. 关闭 Emitter
 * 4. 清理 ThreadLocal 上下文
 *
 * <p>这是 ReAct 循环链路的终点，负责：
 * - 将累积的响应文本封装为最终结果
 * - 通过 SSE 发送 done 事件通知前端
 * - 清理终端会话绑定
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Resource
    private ConversationContextStore conversationContextStore;

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - 发送最终结果");

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        try {
            // 1. 构建最终结果
            ReActResultDTO result = buildFinalResult(dynamicContext);

            // 2. 发送 done SSE 事件
            sendDoneEvent(emitter, result);

            // 3. 关闭 emitter
            emitter.complete();

            log.info("ReAct 完成 - 步数: {}, 工具调用: {}, 停止原因: {}",
                    result.getTotalSteps(),
                    result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode 发送失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            // 4. 将本次 ReAct 执行过程中产生的历史消息和命令回写到会话状态。
            persistConversationContext(dynamicContext);
            // 5. 清理上下文
            cleanup(dynamicContext);
        }
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // ═══════════════════════════════════════════════════════════════
    //  构建最终结果
    // ═══════════════════════════════════════════════════════════════
    /**
     * 构建最终结果 DTO
     */
    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext dynamicContext) {
        String fullText = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        String stopReason = dynamicContext.getStopReason();
        if (stopReason == null) {
            stopReason = StopReasonEnum.COMPLETED.getCode();
        }

        return ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached(StopReasonEnum.MAX_STEPS.getCode().equals(stopReason))
                .userStopped(StopReasonEnum.USER_STOP.getCode().equals(stopReason))
                .idleTimeout(StopReasonEnum.IDLE_TIMEOUT.getCode().equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(dynamicContext.getCurrentToolCalls())
                .toolResults(dynamicContext.getCurrentToolResults())
                .build();
    }

    /**
     * 将本次 ReAct 执行过程中产生的历史消息和命令回写到会话状态。
     */
    private void persistConversationContext(DefaultReActFactory.DynamicContext dynamicContext) {

        String sessionId = dynamicContext.getChatSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            /*
             * 将本次 ReAct 执行过程中产生的历史和命令回写到会话状态。
             */
            conversationContextStore.saveExecutionState(
                    sessionId,
                    dynamicContext.getMessageHistory(),
                    dynamicContext.getRecentCommands()
            );

            log.debug(
                    "ReAct 会话上下文已保存 sessionId={}, historySize={}, commandSize={}",
                    sessionId,
                    dynamicContext.getMessageHistory() == null
                            ? 0
                            : dynamicContext.getMessageHistory().size(),
                    dynamicContext.getRecentCommands() == null
                            ? 0
                            : dynamicContext.getRecentCommands().size()
            );
        } catch (Exception e) {
            log.warn(
                    "ReAct 会话上下文保存失败 sessionId={}",
                    sessionId,
                    e
            );
        }
    }

    /**
     * 清理上下文资源
     */
    private void cleanup(DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            // 清除终端会话绑定
            String sessionId = dynamicContext.getChatSessionId();
            if (sessionId != null) {
                unbindTerminalSession(sessionId);
            }
            // 清除 ThreadLocal
            clearCurrentTerminalSession();
            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }

}
