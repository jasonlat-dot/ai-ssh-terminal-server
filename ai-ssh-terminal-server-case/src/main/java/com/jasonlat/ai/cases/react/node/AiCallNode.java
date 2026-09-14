package com.jasonlat.ai.cases.react.node;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl.SshExecuteAdkTool;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AI 调用节点（ReAct 循环核心）
 *
 * <p>职责：
 * 1. 调用 ADK runner.runAsync() 获取事件流
 * 2. 处理文本内容，发送 SSE 事件
 * 3. 从 event.actions().stateDelta() 检测工具执行结果
 * 4. 如果有工具调用：存储到上下文，发送 SSE 事件，路由到 ToolCallNode
 * 5. 如果无工具调用：路由到 LoopDecisionNode
 * <p>ReAct 循环流程：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用 ADK runner，解析事件）
 *         ├→ [stateDelta 有结果] ToolCallNode → AiCallNode（循环）
 *         └→ [无工具调用] LoopDecisionNode → UserFeedbackNode
 * </pre>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    protected ReActResultDTO doApply(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        String agentId = dynamicContext.getAgentId();

        // 1. 获取 Agent 注册信息和 ADK Runner
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 2. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // 3. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();

        // 4. 绑定终端会话 ID
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
        }

        // 5. 构建用户消息
        Content userContent = Content.builder()
                .role("user")
                // todo 目前仅支持文本 后续可拓展图片等
                .parts(Part.builder().text(lastUserMessage).build())
                .build();

        // 6. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 7. 调用 ADK Runner 并处理事件流
        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        int roundToolCalls = 0;
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            Iterator<Event> events = runner.runAsync(
                    dynamicContext.getUserId(),
                    dynamicContext.getChatSessionId(),
                    userContent,
                    RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build()
            ).blockingIterable().iterator();

            int eventCount = 0;
            while (events.hasNext()) {
                Event event = events.next();
                eventCount++;

                for (FunctionCall call : event.functionCalls()) {
                    String toolCallId = call.id()
                            .filter(id -> !id.isBlank())
                            .orElseThrow(() -> new IllegalStateException("Function call ID is missing"));

                    String toolName = call.name().orElseThrow(() -> new IllegalStateException("Function call name is missing"));

                    Map<String, Object> toolArgs = call.args().orElse(Map.of());

                    String argsJson = objectMapper.writeValueAsString(toolArgs);

                    log.info("检测到真实工具调用: id={}, name={}, args={}", toolCallId, toolName, argsJson);

                    Map<String, Object> toolCallInfo = new HashMap<>();
                    toolCallInfo.put("id", toolCallId);
                    toolCallInfo.put("name", toolName);
                    toolCallInfo.put("args", argsJson);

                    dynamicContext.getCurrentToolCalls().add(toolCallInfo);

                    sendToolCallEvent(emitter, toolCallId, toolName, "executing");

                    roundToolCalls++;
                    dynamicContext.incrementTotalToolCalls();
                }

                for (FunctionResponse response : event.functionResponses()) {

                    String toolCallId = response.id()
                            .filter(id -> !id.isBlank())
                            .orElseThrow(() -> new IllegalStateException("Function response ID is missing"));

                    String toolName = response.name().orElse("");

                    Map<String, Object> result = response.response().orElse(Map.of());
                    String output = String.valueOf(result.getOrDefault("output", ""));
                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    String status = success ? "success" : "error";

                    log.info("检测到真实工具结果: id={}, name={}, result={}", toolCallId, toolName, output);

                    Map<String, Object> toolResultInfo = new HashMap<>();
                    toolResultInfo.put("id", toolCallId);
                    toolResultInfo.put("name", toolName);
                    toolResultInfo.put("content", output);
                    toolResultInfo.put("status", status);

                    dynamicContext.getCurrentToolResults().add(toolResultInfo);

                    sendToolResultEvent(
                            emitter,
                            toolCallId,
                            output,
                            status
                    );
                }

                // 7.1 处理文本内容（模型的响应文本，包括工具调用后的总结）
                String eventText = event.stringifyContent();
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                    dynamicContext.setAssistantContent(textAccumulator);
                    sendTextEvent(emitter, eventText, textAccumulator.toString());
                }

                // 7.3 记录 assistant 内容到消息历史
                if (event.content().isPresent()) {
                    Content content = event.content().get();
                    String role = content.role().orElse("assistant");
                    if ("assistant".equals(role)) {
                        String text = event.stringifyContent();
                        if (!text.isBlank()) {
                            dynamicContext.appendAssistantMessage(text);
                        }
                    }
                }
            }

            log.info("ADK Runner 事件流处理完成，共 {} 个事件", eventCount);

        } catch (Exception e) {
            log.error("ADK Runner 调用失败", e);
            hasError = true;
            errorBuilder.append("ADK Runner error: ").append(e.getMessage());
            dynamicContext.setErrorMessage(errorBuilder.toString());
            dynamicContext.setStopReason(StopReasonEnum.ERROR.getCode());
        } finally {
            // 清除终端会话绑定
            if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
                SshExecuteAdkTool.clearCurrentTerminalSession();
            }
        }

        // 8. 更新步数和工具调用统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(
                dynamicContext.getResult().getTotalToolCalls() + roundToolCalls
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length());

        // 9. 发送本轮结束事件
        sendRoundEndEvent(
                dynamicContext.getEmitter(),
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getResult().getTotalToolCalls()
        );

        // 10. 错误处理
        if (hasError) {
            dynamicContext.setStopReason(StopReasonEnum.ERROR.getCode());
        }

        // 11. 路由
        return router(requestParameter, dynamicContext);
    }


    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 检查是否应该终止
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("检测到终止条件: {}, 路由到 UserFeedbackNode", stopReason);
            return getBean("reactUserFeedbackNode");
        }

        // 检查是否达到最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数 {}, 路由到 UserFeedbackNode", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason(StopReasonEnum.MAX_STEPS.getCode());
            return getBean("reactUserFeedbackNode");
        }

        // 检查本轮是否有工具调用（从 stateDelta 检测到的）
        if (!dynamicContext.getCurrentToolCalls().isEmpty()) {
            log.info("检测到 {} 个工具调用，路由到 ToolCallNode", dynamicContext.getCurrentToolCalls().size());
            return getBean("reactToolCallNode");
        }

        // 无工具调用 → ReAct 循环完成
        log.info("无工具调用，ReAct 循环完成，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }


    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取最新用户消息
     */
    private String getLastUserMessage(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) {
        if (requestParameter.getMessage() != null && !requestParameter.getMessage().isEmpty()) {
            return requestParameter.getMessage();
        }

        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            // 匹配历史消息中用户最后一次的输入信息
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return "";
    }

}
