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
 * ReAct 最终结果与会话回写节点。
 *
 * <p>先发送 done 并正常关闭 SSE，随后在 finally 中把当前 DynamicContext 的历史和最近命令
 * 回写 ConversationContextStore。这里不会关闭 SSH 终端，也不会删除跨请求会话缓存。</p>
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Resource
    private ConversationContextStore conversationContextStore;

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        long nodeStartNanos = System.nanoTime();
        log.info("ReAct链路-UserFeedbackNode 开始 | sessionId:{} | stopReason:{} | step:{} | "
                        + "totalToolCalls:{} | toolResults:{} | historySize:{} | hasError:{}",
                dynamicContext.getChatSessionId(), dynamicContext.getStopReason(), dynamicContext.getStep(),
                dynamicContext.getTotalToolCallCount().get(), dynamicContext.getCurrentToolResults().size(),
                dynamicContext.getMessageHistory().size(), dynamicContext.getErrorMessage() != null);

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        try {
            // 最终正文来自 AiCallNode 累积的 assistantContent，工具明细来自结构化 DTO 列表。
            ReActResultDTO result = buildFinalResult(dynamicContext);
            log.info("ReAct链路-最终结果构建完成 | sessionId:{} | contentLength:{} | stopReason:{} | "
                            + "steps:{} | toolCalls:{} | toolResults:{} | hasError:{}",
                    dynamicContext.getChatSessionId(), safeLength(result.getContent()), result.getStopReason(),
                    result.getTotalSteps(), result.getTotalToolCalls(), sizeOf(result.getToolResults()),
                    result.getError() != null);

            // done 是前端结束当前消息流并固化 UI 状态的协议事件。
            boolean doneSent = sendDoneEvent(emitter, result);
            log.info("ReAct链路-done 事件发送完成 | sessionId:{} | sent:{}",
                    dynamicContext.getChatSessionId(), doneSent);

            // 必须先标记 completed，再 complete；否则 onCompletion 会把正常结束当作客户端断连。
            dynamicContext.getCompleted().set(true);
            emitter.complete();
            log.debug("ReAct链路-SSE emitter 已正常关闭 | sessionId:{} | completed:{}",
                    dynamicContext.getChatSessionId(), dynamicContext.getCompleted().get());

            log.info("ReAct链路-UserFeedbackNode 输出完成 | sessionId:{} | steps:{} | "
                            + "toolCalls:{} | stopReason:{} | durationMs:{}",
                    dynamicContext.getChatSessionId(), result.getTotalSteps(), result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed",
                    elapsedMillis(nodeStartNanos));

            return result;

        } catch (Exception e) {
            log.error("ReAct链路-UserFeedbackNode 发送失败 | sessionId:{} | durationMs:{}",
                    dynamicContext.getChatSessionId(), elapsedMillis(nodeStartNanos), e);
            try {
                emitter.completeWithError(e);
                log.debug("ReAct链路-SSE emitter 已按异常关闭 | sessionId:{}",
                        dynamicContext.getChatSessionId());
            } catch (Exception completeError) {
                log.debug("ReAct链路-SSE emitter 异常关闭失败，可能已关闭 | sessionId:{} | reason:{}",
                        dynamicContext.getChatSessionId(), completeError.getMessage());
            }
            throw e;
        } finally {
            // 即使发送 done 或关闭 emitter 失败，也尽量保存已产生的业务历史。
            persistConversationContext(dynamicContext);
            // 当前 cleanup 只保留扩展点，不删除 ConversationContextStore 或 SSH 会话资源。
            cleanup(dynamicContext);
        }
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.debug("ReAct链路-UserFeedbackNode 到达链路末端 | sessionId:{}",
                dynamicContext.getChatSessionId());
        return defaultStrategyHandler;
    }

    // ═══════════════════════════════════════════════════════════════
    //  构建最终结果
    // ═══════════════════════════════════════════════════════════════
    /**
     * 构建最终结果 DTO
     */
    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext dynamicContext) {
        long buildStartNanos = System.nanoTime();
        String fullText = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        String stopReason = dynamicContext.getStopReason();
        if (stopReason == null) {
            stopReason = StopReasonEnum.COMPLETED.getCode();
        }

        ReActResultDTO result = ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getTotalToolCallCount().get())
                .maxStepsReached(StopReasonEnum.MAX_STEPS.getCode().equals(stopReason))
                .userStopped(StopReasonEnum.USER_STOP.getCode().equals(stopReason))
                .idleTimeout(StopReasonEnum.IDLE_TIMEOUT.getCode().equals(stopReason))
                .stopReason(stopReason)
                .error(dynamicContext.getErrorMessage())
                // executedToolCalls 保留本次请求内实际观察到的全部工具调用；结果按 toolCallId 对齐。
                .toolCalls(dynamicContext.getExecutedToolCalls())
                .toolResults(dynamicContext.getCurrentToolResults())
                .build();
        log.debug("ReAct链路-buildFinalResult 完成 | sessionId:{} | contentLength:{} | "
                        + "toolCalls:{} | toolResults:{} | durationMs:{}",
                dynamicContext.getChatSessionId(), fullText.length(),
                sizeOf(dynamicContext.getExecutedToolCalls()), sizeOf(dynamicContext.getCurrentToolResults()),
                elapsedMillis(buildStartNanos));
        return result;
    }

    /**
     * 将本次 ReAct 执行过程中产生的历史消息和命令回写到会话状态。
     */
    private void persistConversationContext(DefaultReActFactory.DynamicContext dynamicContext) {

        String sessionId = dynamicContext.getChatSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("ReAct链路-跳过业务会话回写：sessionId 为空");
            return;
        }

        long persistStartNanos = System.nanoTime();
        try {
            log.info("ReAct链路-开始回写业务会话 | sessionId:{} | historySize:{} | recentCommands:{}",
                    sessionId, sizeOf(dynamicContext.getMessageHistory()),
                    sizeOf(dynamicContext.getRecentCommands()));
            // 整体覆盖同一会话状态；ServiceCase 的 session lock 保证同一 session 不会并发覆盖。
            conversationContextStore.saveExecutionState(
                    sessionId,
                    dynamicContext.getMessageHistory(),
                    dynamicContext.getRecentCommands()
            );

            log.info(
                    "ReAct链路-业务会话回写完成 | sessionId:{} | historySize:{} | recentCommands:{} | durationMs:{}",
                    sessionId,
                    dynamicContext.getMessageHistory() == null
                            ? 0
                            : dynamicContext.getMessageHistory().size(),
                    dynamicContext.getRecentCommands() == null
                            ? 0
                            : dynamicContext.getRecentCommands().size(),
                    elapsedMillis(persistStartNanos)
            );
        } catch (Exception e) {
            log.warn(
                    "ReAct链路-业务会话回写失败 | sessionId:{} | historySize:{} | recentCommands:{} | durationMs:{}",
                    sessionId, sizeOf(dynamicContext.getMessageHistory()),
                    sizeOf(dynamicContext.getRecentCommands()), elapsedMillis(persistStartNanos), e
            );
        }
    }

    /**
     * 清理上下文资源
     */
    private void cleanup(DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            String sessionId = dynamicContext.getChatSessionId();
            log.debug("ReAct链路-请求上下文清理扩展点完成 | sessionId:{} | completed:{} | cancelled:{}",
                    sessionId, dynamicContext.getCompleted().get(), dynamicContext.getCancelled().get());
        } catch (Exception e) {
            log.warn("ReAct链路-请求上下文清理扩展点异常 | sessionId:{} | reason:{}",
                    dynamicContext.getChatSessionId(), e.getMessage());
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private int sizeOf(java.util.List<?> values) {
        return values == null ? 0 : values.size();
    }

}
