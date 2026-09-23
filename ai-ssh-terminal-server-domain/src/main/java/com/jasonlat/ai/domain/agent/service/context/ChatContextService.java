package com.jasonlat.ai.domain.agent.service.context;


import com.jasonlat.ai.domain.agent.model.valobj.enums.ContextPlacement;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.jasonlat.ai.domain.agent.service.IChatContextService;
import com.jasonlat.ai.domain.agent.service.context.provider.ContextProvider;
import com.jasonlat.ai.domain.agent.service.context.provider.impl.ToolResultProvider;
import com.jasonlat.ai.domain.agent.service.context.reducer.impl.HybridReducer;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 上下文管理领域服务（context 包的聚合核心）
 * <p>
 * 功能：ReAct 对话的"上下文中枢"，对上（PromptService/Case层）提供三个能力：
 * <pre>
 *   buildPromptContext()  聚合所有 Provider 的输出 --> PromptContextVO
 *   trimHistory()         在 token 预算内裁剪消息历史（默认 8000K）
 *   pushToolResult()      接收工具执行结果，供生成工具摘要
 * </pre>
 * 整体运行过程（一次 ReAct 循环中的调用时序）：
 * <pre>
 *   AiCallNode.doApply()
 *        |
 *        | (1) trimHistory(history, 8000)
 *        |        |
 *        |        v
 *        |     HybridReducer = PriorityReducer ∩ SlidingWindowReducer + 配置化保底条数
 *        |        |
 *        |        v
 *        |     裁剪后的历史回写 DynamicContext
 *        |
 *        | (2) PromptService.buildEnrichedMessage(...)
 *        |        |
 *        |        v
 *        |     buildPromptContext(sessionId, userId, terminalSessionId, history)
 *        |        |
 *        |        +--> for provider in providers(按order排序, 跳过disabled):
 *        |        |        TerminalState(10)  {osInfo, currentUser, currentDirectory}
 *        |        |        Task(20)           {taskDescription}
 *        |        |        Milestone(30)      {milestoneVOS}
 *        |        |        ToolResult(40)     {toolResultSummary}
 *        |        |     finalCtx.putAll(...)   合并所有键值对
 *        |        |
 *        |        v
 *        |     PromptContextVO --> DynamicPromptBuilder --> 消息前缀
 *        |
 *        | (3) 工具执行后 pushToolResult(sessionId, toolName, result)
 *        |        |
 *        |        v
 *        |     ToolResultProvider.pushResult() 缓存 + 使摘要失效
 *        |     （下一轮 (2) 时新摘要进入 Prompt）
 * </pre>
 * 装配机制：@Resource List<ContextProvider> 由 Spring 按类型自动收集
 * 全部 Provider 实现，@PostConstruct 时按 getOrder() 排序——新增 Provider
 * 只需加 @Component，无需修改本类。
 */
@Service
public class ChatContextService implements IChatContextService {

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final List<ContextProvider> providers;
    
    @Resource
    private HybridReducer hybridReducer;
    
    @Resource
    private ToolResultProvider toolResultProvider;

    // 构造函数注入；[Spring Dependency Injection
    public ChatContextService(List<ContextProvider> providers) {
        this.providers = providers;
        this.providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();

        Map<String, Object> stableContext = new HashMap<>();
        Map<String, Object> ephemeralContext = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            Map<String, Object> ctx = provider.provide(sessionId, userId, terminalSessionId, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
                if (provider.getPlacement() == ContextPlacement.STABLE_PREFIX) {
                    stableContext.putAll(ctx);
                } else {
                    ephemeralContext.putAll(ctx);
                }
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .uptime((String) finalCtx.get("uptime"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                // 长期记忆摘要：由 LongTermMemoryProvider(order=25) 召回并注入，
                // 经 DynamicPromptBuilder 渲染为 [长期记忆] 段落拼到用户消息前面。
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .stableContext(stableContext)
                .ephemeralContext(ephemeralContext)
                .build();
    }

    /**
     * 在 token 预算内裁剪消息历史
     *
     * @param history     原始消息历史
     * @param tokenBudget token 预算（<=0 时使用默认值）
     * @return 裁剪后的消息历史
     */
    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        // 混合裁剪
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }
    
    @Override
    public void pushToolResult(String sessionId, String toolName,String args, String result) {
        toolResultProvider.pushResult(sessionId, toolName, args, result);
    }

    /**
     * 清理指定会话的上下文缓存。
     *
     * <p>当前主要清理的是 ToolResultProvider 内部维护的会话级工具结果缓存，
     * 用于避免一次对话结束后，上一轮工具执行结果继续污染下一轮上下文。
     *
     * <p>案例：
     * <pre>
     *   session-001 在本轮执行中累计了：
     *   - ls /var/log 结果
     *   - tail error.log 结果
     *   - cat nginx.conf 结果
     *
     *   当本轮完成，调用：
     *   clearSessionContext("session-001")
     *
     *   效果：
     *   ToolResultProvider.clear("session-001")
     *   -> 清空该会话的工具结果缓存和对应摘要缓存
     * </pre>
     *
     * <p>这样下一次新的会话轮次开始时，不会继续沿用上一次已经失效的工具执行结果。
     *
     * @param sessionId 当前会话 ID
     */
    @Override
    public void clearSessionContext(String sessionId) {
        if (sessionId != null) {
            toolResultProvider.clearResult(sessionId);
        }
    }

}
