package com.jasonlat.ai.cases.react.node;

import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 单次 ADK invocation 的结束条件收口节点。
 *
 * <p>当前架构由 ADK 在 {@code runAsync} 内完成模型与工具的 ReAct 循环，本节点不会再路由
 * 回 AiCallNode。它只把取消、保护阈值、显式 finish、错误或正常完成统一转换为 stopReason，
 * 再交给 UserFeedbackNode 生成最终结果。</p>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        long nodeStartNanos = System.nanoTime();
        log.info("ReAct链路-LoopDecisionNode 开始 | sessionId:{} | step:{}/{} | totalToolCalls:{}/{} | "
                        + "currentToolCalls:{} | currentToolResults:{} | existingStopReason:{} | hasError:{}",
                dynamicContext.getChatSessionId(), dynamicContext.getStep(), dynamicContext.getMaxSteps(),
                dynamicContext.getTotalToolCallCount().get(), dynamicContext.getMaxToolCalls(),
                dynamicContext.getCurrentToolCalls().size(), dynamicContext.getCurrentToolResults().size(),
                dynamicContext.getStopReason(), dynamicContext.getErrorMessage() != null);

        // 取消、工具结果缺失或 Runner 异常可能已经在前序节点设置 stopReason，应优先保留。
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("ReAct链路-沿用前序终止原因 | sessionId:{} | stopReason:{} | durationMs:{}",
                    dynamicContext.getChatSessionId(), stopReason, elapsedMillis(nodeStartNanos));
            return router(requestParameter, dynamicContext);
        }

        // step 统计外层 ADK invocation 次数，是 Case 层的兜底保护。
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.warn("ReAct链路-达到最大步数 | sessionId:{} | step:{} | maxSteps:{}",
                    dynamicContext.getChatSessionId(), dynamicContext.getStep(), dynamicContext.getMaxSteps());
            dynamicContext.setStopReason(StopReasonEnum.MAX_STEPS.getCode());
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 工具总量在观察到唯一 FunctionCall 时递增，防止一次 invocation 内工具调用失控。
        if (dynamicContext.getTotalToolCallCount().get() >= dynamicContext.getMaxToolCalls()) {
            log.warn("ReAct链路-达到最大工具调用次数 | sessionId:{} | totalToolCalls:{} | maxToolCalls:{}",
                    dynamicContext.getChatSessionId(), dynamicContext.getTotalToolCallCount().get(),
                    dynamicContext.getMaxToolCalls());
            dynamicContext.setStopReason(StopReasonEnum.MAX_TOOL_CALLS.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 兼容 Prompt 约定的显式完成标记；普通自然结束在最后归为 COMPLETED。
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        if (containsFinishCommand(assistantContent)) {
            log.info("ReAct链路-检测到模型 finish 指令 | sessionId:{} | assistantLength:{}",
                    dynamicContext.getChatSessionId(), assistantContent.length());
            dynamicContext.setStopReason(StopReasonEnum.FINISH.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 防御性检查：正常情况下设置 errorMessage 的节点也会同步设置 ERROR stopReason。
        if (dynamicContext.getErrorMessage() != null) {
            log.warn("ReAct链路-检测到错误状态 | sessionId:{} | error:{}",
                    dynamicContext.getChatSessionId(), dynamicContext.getErrorMessage());
            dynamicContext.setStopReason(StopReasonEnum.ERROR.getCode());
            return router(requestParameter, dynamicContext);
        }

        // runAsync 已自然结束且无任何保护条件命中，视为本次请求正常完成。
        log.info("ReAct链路-ADK invocation 正常结束 | sessionId:{} | assistantLength:{} | durationMs:{}",
                dynamicContext.getChatSessionId(), assistantContent.length(), elapsedMillis(nodeStartNanos));
        dynamicContext.setStopReason(StopReasonEnum.COMPLETED.getCode());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        StopReasonEnum stopReasonEnum = StopReasonEnum.getByCode(dynamicContext.getStopReason());
        log.debug("ReAct链路-同步结果状态标志 | sessionId:{} | stopReason:{} | resolvedEnum:{}",
                dynamicContext.getChatSessionId(), dynamicContext.getStopReason(), stopReasonEnum);

        if (stopReasonEnum != null) {
            switch (stopReasonEnum) {
                case USER_STOP:
                    dynamicContext.getResult().setUserStopped(true);
                    break;
                case IDLE_TIMEOUT:
                    dynamicContext.getResult().setIdleTimeout(true);
                    break;
                case MAX_STEPS:
                    dynamicContext.getResult().setMaxStepsReached(true);
                    break;
                case COMPLETED:
                case FINISH:
                case ERROR:
                case MAX_TOOL_CALLS:
                default:
                    break;
            }
        }

        // 到达此处意味着 runAsync 已结束
        log.info("ReAct链路-LoopDecisionNode 路由 | sessionId:{} | nextNode:UserFeedbackNode | "
                        + "stopReason:{} | userStopped:{} | idleTimeout:{} | maxStepsReached:{}",
                dynamicContext.getChatSessionId(), dynamicContext.getStopReason(),
                dynamicContext.getResult().isUserStopped(), dynamicContext.getResult().isIdleTimeout(),
                dynamicContext.getResult().isMaxStepsReached());

        return getBean("reactUserFeedbackNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查是否包含终止指令
     * 参考 WaLiCode streamingAgent.ts 的终止条件判断
     */
    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String lowerContent = content.toLowerCase();
        boolean containsFinish = lowerContent.contains("<finish>")
                || lowerContent.contains("[finish]")
                || lowerContent.contains("action: finish");
        log.debug("ReAct链路-finish 指令检查完成 | contentLength:{} | matched:{}",
                content.length(), containsFinish);
        return containsFinish;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

}
