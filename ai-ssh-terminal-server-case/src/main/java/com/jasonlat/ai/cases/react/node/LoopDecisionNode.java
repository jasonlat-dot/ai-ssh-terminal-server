package com.jasonlat.ai.cases.react.node;

import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ReAct 循环决策节点
 *
 * <p>职责：
 * 1. 检查终止条件（错误、最大步数、用户停止）
 * 2. 决定是否继续循环（回到 AiCallNode）或路由到 UserFeedbackNode
 *
 * <p>终止条件（参考 WaLiCode streamingAgent.ts）：
 * - AI 返回 finish 终止指令
 * - 达到最大步数 (maxSteps)
 * - 达到最大工具调用次数 (maxToolCalls)
 * - 发生错误
 * - 用户主动停止 (user_stop)
 *
 * <p>循环条件：
 * - 上一轮有工具调用（继续对话）
 * - AI 未返回终止指令
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct LoopDecisionNode - 循环决策，当前步数: {}/{}", dynamicContext.getStep(), dynamicContext.getMaxSteps());

        // 1. 检查是否已有终止原因
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("已设置终止原因: {}", stopReason);
            return router(requestParameter, dynamicContext);
        }

        // 2. 检查最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数: {}, 终止循环", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason(StopReasonEnum.MAX_STEPS.getCode());
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 3. 检查最大工具调用次数
        if (dynamicContext.getResult().getTotalToolCalls() >= dynamicContext.getMaxToolCalls()) {
            log.info("达到最大工具调用次数: {}, 终止循环",
                    dynamicContext.getResult().getTotalToolCalls());
            dynamicContext.setStopReason(StopReasonEnum.MAX_TOOL_CALLS.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 4. 检查 assistant 消息是否包含终止指令
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        if (containsFinishCommand(assistantContent)) {
            log.info("AI 返回 finish 指令，终止循环");
            dynamicContext.setStopReason(StopReasonEnum.FINISH.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 5. 检查错误
        if (dynamicContext.getErrorMessage() != null) {
            log.info("发生错误: {}, 终止循环", dynamicContext.getErrorMessage());
            dynamicContext.setStopReason(StopReasonEnum.ERROR.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 6. 检查上一轮是否有工具调用（继续 ReAct 循环的条件）
        //    如果上一轮有工具调用，说明 AI 还在通过工具完成任务，需要继续对话
        List<Map<String, Object>> currentToolCalls = dynamicContext.getCurrentToolCalls();
        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
            log.info("上一轮有 {} 个工具调用，继续 ReAct 循环", currentToolCalls.size());
            // 清空当前轮次缓冲，准备下一轮
            dynamicContext.resetRoundBuffers();
            // 路由回 AiCallNode
            return router(requestParameter, dynamicContext);
        }

        // 7. 无工具调用且无终止指令 → 循环完成
        log.info("ReAct 循环完成，无更多工具调用");
        dynamicContext.setStopReason(StopReasonEnum.COMPLETED.getCode());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        StopReasonEnum stopReasonEnum = StopReasonEnum.getByCode(dynamicContext.getStopReason());
        // 有终止原因 → 路由到 UserFeedbackNode
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
            return getBean("reactUserFeedbackNode");
        }

        // 无终止原因 → 继续 ReAct 循环，回到 AiCallNode
        log.info("继续 ReAct 循环，路由到 AiCallNode");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查是否包含终止指令
     * 参考 WaLiCode streamingAgent.ts 的终止条件判断
     */
    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) return false;

        String lower = content.toLowerCase();

        // DSL 风格: finish(message=...)
        if (lower.contains("finish(") || lower.contains("finish (")) {
            return true;
        }

        // JSON 风格: {"action": "finish"}
        if (lower.contains("\"action\"") && lower.contains("\"finish\"")) {
            return true;
        }

        // 标签风格: <answer>finish(...)</answer>
        return lower.contains("<answer>") && lower.contains("finish");
    }

}
