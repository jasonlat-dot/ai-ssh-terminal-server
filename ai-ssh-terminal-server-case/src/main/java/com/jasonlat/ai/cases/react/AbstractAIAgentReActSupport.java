package com.jasonlat.ai.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActEventDTO;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.ai.trigger.api.dto.enums.ReActEventTypeEnum;
import com.jasonlat.ai.trigger.api.dto.enums.ToolStatusEnum;
import com.jasonlat.design.framework.tree.AbstractMultiThreadStrategyRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 支撑类（抽象基类）
 *
 * <p>封装节点共享的 Spring Bean 路由和 SSE 协议序列化能力。本类不执行模型或工具，
 * 也不持有跨请求状态。</p>
 *
 * <p>节点路由链：RootNode → AiCallNode →（可选 ToolCallNode）→
 * LoopDecisionNode → UserFeedbackNode。</p>
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 通用的 Bean 获取
     */
    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        T bean = (T) applicationContext.getBean(beanName);
        log.debug("ReAct链路-解析路由 Bean | beanName:{} | beanType:{}",
                beanName, bean.getClass().getSimpleName());
        return bean;
    }

    @Override
    protected void multiThread(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("ReAct链路-节点异步预加载跳过 | sessionId:{} | node:{}",
                dynamicContext == null ? null : dynamicContext.getChatSessionId(),
                getClass().getSimpleName());
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射辅助
    // ═══════════════════════════════════════════════════════════════

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** 发送一个流式文本增量；返回 false 表示 SSE 已不可写，调用方应触发取消。 */
    protected boolean sendTextEvent(ResponseBodyEmitter emitter, String content, String fullText) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.TEXT.getCode());
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.debug("ReAct链路-SSE text 已发送 | chunkLength:{} | fullTextLength:{}",
                    safeLength(content), safeLength(fullText));
            return true;
        } catch (Exception e) {
            log.warn("ReAct链路-SSE text 发送失败 | chunkLength:{} | fullTextLength:{} | reason:{}",
                    safeLength(content), safeLength(fullText), e.getMessage());
            return false;
        }
    }

    /** 通知前端工具进入运行状态；该方法本身不执行工具。 */
    protected boolean sendToolCallEvent(ResponseBodyEmitter emitter, String toolCallId, String toolName, String commend, ToolStatusEnum status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.TOOL_CALL.getCode());
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setStatus(status.getCode());
            event.setCommend(commend);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.debug("ReAct链路-SSE tool_call 已发送 | toolCallId:{} | toolName:{} | status:{} | argsLength:{}",
                    toolCallId, toolName, status.getCode(), safeLength(commend));
            return true;
        } catch (Exception e) {
            log.warn("ReAct链路-SSE tool_call 发送失败 | toolCallId:{} | toolName:{} | reason:{}",
                    toolCallId, toolName, e.getMessage());
            return false;
        }
    }

    /** 按 toolCallId 推送真实或合成的工具结果，供前端更新对应工具卡片。 */
    protected boolean sendToolResultEvent(ResponseBodyEmitter emitter, String toolCallId, String content, ToolStatusEnum status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.TOOL_RESULT.getCode());
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status.getCode());
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.debug("ReAct链路-SSE tool_result 已发送 | toolCallId:{} | status:{} | contentLength:{}",
                    toolCallId, status.getCode(), safeLength(content));
            return true;
        } catch (Exception e) {
            log.warn("ReAct链路-SSE tool_result 发送失败 | toolCallId:{} | status:{} | reason:{}",
                    toolCallId, status.getCode(), e.getMessage());
            return false;
        }
    }

    /**
     * 发送步数结束事件
     */
    protected boolean sendRoundEndEvent(ResponseBodyEmitter emitter, int currentStep, int maxSteps, int totalToolCalls) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.ROUND_END.getCode());
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("ReAct链路-SSE round_end 已发送 | currentStep:{} | maxSteps:{} | totalToolCalls:{}",
                    currentStep, maxSteps, totalToolCalls);
            return true;

        } catch (Exception e) {
            log.warn("ReAct链路-SSE round_end 发送失败 | currentStep:{} | totalToolCalls:{} | reason:{}",
                    currentStep, totalToolCalls, e.getMessage());
            return false;
        }
    }

    /**
     * 发送完成事件
     */
    protected boolean sendDoneEvent(ResponseBodyEmitter emitter, ReActResultDTO result) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.DONE.getCode());
            event.setContent(objectMapper.writeValueAsString(result));
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("ReAct链路-SSE done 已发送 | stopReason:{} | contentLength:{} | toolCalls:{} | toolResults:{}",
                    result.getStopReason(), safeLength(result.getContent()), result.getTotalToolCalls(),
                    result.getToolResults() == null ? 0 : result.getToolResults().size());
            return true;
        } catch (Exception e) {
            log.warn("ReAct链路-SSE done 发送失败 | stopReason:{} | reason:{}",
                    result == null ? null : result.getStopReason(), e.getMessage());
            return false;
        }
    }

    /**
     * 发送可被前端直接识别的错误事件。
     */
    protected void sendErrorEvent(ResponseBodyEmitter emitter, String message) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent(ReActEventTypeEnum.ERROR.getCode());
            event.setContent(message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("ReAct链路-SSE error 已发送 | messageLength:{}", safeLength(message));
        } catch (Exception e) {
            log.warn("ReAct链路-SSE error 发送失败 | messageLength:{} | reason:{}",
                    safeLength(message), e.getMessage());
        }
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }


}
