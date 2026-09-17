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
 * ReAct Root Node（根节点）
 *
 * <p>职责：
 * 1. 从 ChatRequestDTO 提取会话参数
 * 2. 初始化 DynamicContext
 * 3. 绑定终端会话 ID（ThreadLocal 和 Session 映射双重绑定）
 * 4. 路由到 AiCallNode
 *
 * <p>节点链：
 * RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private ConversationContextStore conversationContextStore;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        // 2. 绑定终端会话（ThreadLocal + 映射绑定，支持异步和跨请求继承）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            setCurrentTerminalSession(terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
        } else {
            // 尝试从会话绑定中恢复
            String boundTerminal = getTerminalSession(sessionId);
            if (boundTerminal != null) {
                setCurrentTerminalSession(boundTerminal);
                dynamicContext.setTerminalSessionId(boundTerminal); // 补齐 dynamicContext 中的值
            }
        }

        // 3. 初始化上下文
        ConversationContextStore.ConversationContextSnapshot snapshot = conversationContextStore.initializeAndLoad(sessionId, message);

        dynamicContext.setChatSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);
        if (dynamicContext.getTerminalSessionId() == null) {
            dynamicContext.setTerminalSessionId(terminalSessionId);
        }
        dynamicContext.setMessageHistory(snapshot.getMessageHistory());
        dynamicContext.setRecentCommands(snapshot.getRecentCommands());
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());
        dynamicContext.setCurrentStep(new AtomicInteger(0));
        dynamicContext.setMaxSteps(DEFAULT_MAX_STEPS);
        dynamicContext.setMaxToolCalls(DEFAULT_MAX_TOOL_CALLS);
        dynamicContext.setMaxToolCallsPerRound(DEFAULT_MAX_TOOL_CALLS_PER_ROUND);

        // 4. 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. 追加用户消息到历史
        dynamicContext.appendUserMessage(message);

        log.info("ReAct RootNode - 初始化完成 sessionId={}, userId={}, agentId={}, terminalSessionId={}",
                sessionId, userId, agentId, dynamicContext.getTerminalSessionId());

        // 6. 路由到 AI 调用节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }
}
