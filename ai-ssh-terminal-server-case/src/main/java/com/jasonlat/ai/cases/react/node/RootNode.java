package com.jasonlat.ai.cases.react.node;

import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 请求入口节点。
 *
 * <p>每个 HTTP 请求都会创建新的 {@link DefaultReActFactory.DynamicContext}，本节点负责把
 * {@link ConversationContextStore} 中的跨请求会话状态加载到该临时上下文。两者的生命周期不同：
 * DynamicContext 只服务当前执行链，ConversationContextStore 才是业务历史的持久来源。</p>
 *
 * <p>节点链：RootNode → AiCallNode →（观察到工具时经过 ToolCallNode）→
 * LoopDecisionNode → UserFeedbackNode。</p>
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private ConversationContextStore conversationContextStore;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_LLM_CALLS = 20;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        long nodeStartNanos = System.nanoTime();

        // 请求标识必须逐次写入 DynamicContext，尤其不能通过全局字段保存 terminalSessionId。
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();
        log.info("ReAct链路-RootNode 开始 | sessionId:{} | userId:{} | agentId:{} | "
                        + "terminalSessionId:{} | messageLength:{}",
                sessionId, userId, agentId, terminalSessionId, message == null ? 0 : message.length());

        // initializeAndLoad 会确保会话存在，并返回当前请求开始前的业务历史与命令摘要。
        long loadStartNanos = System.nanoTime();
        ConversationContextStore.ConversationContextSnapshot snapshot = conversationContextStore.initializeAndLoad(sessionId, message);
        log.info("ReAct链路-业务会话快照加载完成 | sessionId:{} | historySize:{} | recentCommands:{} | "
                        + "originalTaskPresent:{} | durationMs:{}",
                sessionId, snapshot.getMessageHistory().size(), snapshot.getRecentCommands().size(),
                snapshot.getOriginalTask() != null && !snapshot.getOriginalTask().isBlank(),
                elapsedMillis(loadStartNanos));

        dynamicContext.setChatSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);
        dynamicContext.setTerminalSessionId(terminalSessionId);
        dynamicContext.setMessageHistory(snapshot.getMessageHistory());
        dynamicContext.setRecentCommands(snapshot.getRecentCommands());
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());
        dynamicContext.setCurrentStep(new AtomicInteger(0));
        dynamicContext.setMaxSteps(DEFAULT_MAX_STEPS);
        dynamicContext.setMaxLlmCalls(DEFAULT_MAX_LLM_CALLS);
        dynamicContext.setMaxToolCalls(DEFAULT_MAX_TOOL_CALLS);
        dynamicContext.setMaxToolCallsPerRound(DEFAULT_MAX_TOOL_CALLS_PER_ROUND);
        log.debug("ReAct链路-请求上下文字段初始化完成 | sessionId:{} | historySize:{} | recentCommands:{} | "
                        + "maxSteps:{} | maxLlmCalls:{} | maxToolCalls:{} | maxToolCallsPerRound:{}",
                sessionId, dynamicContext.getMessageHistory().size(), dynamicContext.getRecentCommands().size(),
                dynamicContext.getMaxSteps(), dynamicContext.getMaxLlmCalls(),
                dynamicContext.getMaxToolCalls(), dynamicContext.getMaxToolCallsPerRound());

        // 初始化当前请求的计数器、保护阈值和结果容器；这些状态不会跨请求复用。
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 上下文就绪后进入本次请求唯一的 ADK invocation。
        log.info("ReAct链路-RootNode 路由 | sessionId:{} | nextNode:AiCallNode | initDurationMs:{}",
                sessionId, elapsedMillis(nodeStartNanos));
        ReActResultDTO finalResult = router(requestParameter, dynamicContext);
        log.info("ReAct链路-RootNode 返回 | sessionId:{} | stopReason:{} | totalDurationMs:{}",
                sessionId, finalResult == null ? null : finalResult.getStopReason(), elapsedMillis(nodeStartNanos));
        return finalResult;
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.debug("ReAct链路-RootNode 解析下一节点 | sessionId:{} | bean:reactAiCallNode",
                dynamicContext.getChatSessionId());
        return getBean("reactAiCallNode");
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
