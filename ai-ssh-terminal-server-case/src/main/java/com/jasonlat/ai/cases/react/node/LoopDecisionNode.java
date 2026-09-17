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
 * 1. 检查终止条件（错误、最大步数、用户停止、最大工具调用次数）
 * 2. 在 ADK 主循环模式下，作为结束节点收口，统一路由到 UserFeedbackNode
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct LoopDecisionNode - 检查结束条件，当前步数: {}/{}", dynamicContext.getStep(), dynamicContext.getMaxSteps());

        // 1. 检查是否已有终止原因
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("已设置终止原因: {}", stopReason);
            return router(requestParameter, dynamicContext);
        }

        // 2. 检查最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数: {}, 终止处理", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason(StopReasonEnum.MAX_STEPS.getCode());
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 3. 检查最大工具调用次数
        if (dynamicContext.getTotalToolCallCount().get() >= dynamicContext.getMaxToolCalls()) {
            log.info("达到最大工具调用次数: {}, 终止处理",
                    dynamicContext.getResult().getTotalToolCalls());
            dynamicContext.setStopReason(StopReasonEnum.MAX_TOOL_CALLS.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 4. 检查 assistant 消息是否包含终止指令
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        if (containsFinishCommand(assistantContent)) {
            log.info("AI 返回 finish 指令，终止处理");
            dynamicContext.setStopReason(StopReasonEnum.FINISH.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 5. 检查错误
        if (dynamicContext.getErrorMessage() != null) {
            log.info("发生错误: {}, 终止处理", dynamicContext.getErrorMessage());
            dynamicContext.setStopReason(StopReasonEnum.ERROR.getCode());
            return router(requestParameter, dynamicContext);
        }

        // 6. 无异常且 ADK 自动执行结束，视为正常 completed
        log.info("ReAct 循环完成，无更多工具调用");
        dynamicContext.setStopReason(StopReasonEnum.COMPLETED.getCode());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        StopReasonEnum stopReasonEnum = StopReasonEnum.getByCode(dynamicContext.getStopReason());

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

        // 在当前架构下，ADK 包办了工具链循环。到达此处意味着一次 runAsync 已跑完，直接输出结果
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
        if (content == null || content.isBlank()) return false;

        String lowerContent = content.toLowerCase();
        return lowerContent.contains("<finish>")
                || lowerContent.contains("[finish]")
                || lowerContent.contains("action: finish");
    }

}
