package com.jasonlat.ai.cases.react.node;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.IChatContextService;
import com.jasonlat.ai.domain.agent.service.IPromptService;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import com.jasonlat.ai.domain.agent.service.util.AgentUtils;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.ai.trigger.api.dto.ToolCallDTO;
import com.jasonlat.ai.trigger.api.dto.ToolResultDTO;
import com.jasonlat.ai.trigger.api.dto.enums.ToolStatusEnum;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 单次 ADK invocation 的执行与事件桥接节点。
 *
 * <p>跨 HTTP 请求的对话历史由 ConversationContextStore/Reducer 管理；执行前将裁剪结果
 * 临时投影到 CustomAdkSessionService。当前 invocation 内的 ReAct 工具循环完全由 ADK
 * 负责，本节点只观察事件、维护业务历史并发送 SSE，不会再次手动执行工具。</p>
 *
 * <p>事件必须按 ADK 返回顺序处理。模型文本、FunctionCall、FunctionResponse 可能交错出现，
 * 因此遇到工具边界时要先落盘当前文本片段，才能在业务历史中保持
 * “assistant 文本 → assistant tool_calls → tool 结果 → assistant 文本”的真实顺序。</p>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;
    @Resource
    private IPromptService promptService;
    @Resource
    private IChatContextService chatContextService;

    @Override
    protected ReActResultDTO doApply(ChatRequest request, DefaultReActFactory.DynamicContext context) throws Exception {
        long nodeStartNanos = System.nanoTime();
        log.info("ReAct链路-AiCallNode 开始 | sessionId:{} | userId:{} | agentId:{} | step:{} | "
                        + "historySize:{} | recentCommands:{}",
                context.getChatSessionId(), context.getUserId(), context.getAgentId(), context.getStep() + 1,
                sizeOf(context.getMessageHistory()), sizeOf(context.getRecentCommands()));

        // Armory 在启动阶段已装配 Agent、Runner、工具和插件；Case 层只按 agentId 取用。
        long lookupStartNanos = System.nanoTime();
        AiAgentRegisterVO registration = defaultArmoryFactory.getAiAgentRegisterVO(context.getAgentId());
        if (registration == null) {
            log.error("ReAct链路-Agent 注册信息不存在 | sessionId:{} | agentId:{} | lookupDurationMs:{}",
                    context.getChatSessionId(), context.getAgentId(), elapsedMillis(lookupStartNanos));
            throw new IllegalStateException("Agent not found: " + context.getAgentId());
        }

        Runner runner = registration.getRunner();
        String userMessage = request.getMessage();
        log.info("ReAct链路-Agent Runner 获取完成 | sessionId:{} | agentId:{} | appName:{} | "
                        + "runnerType:{} | sessionServiceType:{} | durationMs:{}",
                context.getChatSessionId(), context.getAgentId(), runner.appName(),
                runner.getClass().getSimpleName(), runner.sessionService().getClass().getSimpleName(),
                elapsedMillis(lookupStartNanos));
        // 清空的是本次请求的输出缓冲，不清空 RootNode 刚加载的跨请求历史。
        context.resetRoundBuffers();
        context.resetRoundToolCalls();
        context.setStopReason(null);
        context.setErrorMessage(null);
        log.debug("ReAct链路-本轮缓冲已重置 | sessionId:{} | currentToolCalls:{} | "
                        + "currentToolResults:{} | roundToolCalls:{}",
                context.getChatSessionId(), context.getCurrentToolCalls().size(),
                context.getCurrentToolResults().size(), context.getRoundToolCallCount().get());

        /*
         * 业务历史是唯一事实来源，ADK Session 只是本次调用的临时投影。
         * RootNode 加载的是当前请求之前的历史，当前 user 会通过 runAsync 的 userContent
         * 单独传入，因此裁剪结果可以直接投影给 ADK。
         */
        int historySizeBeforeTrim = context.getMessageHistory() == null
                ? 0
                : context.getMessageHistory().size();
        long trimStartNanos = System.nanoTime();
        log.info("ReAct链路-调用历史裁剪 | sessionId:{} | inputMessages:{} | tokenBudget:{}",
                context.getChatSessionId(), historySizeBeforeTrim, 0);
        List<Map<String, Object>> trimmedHistory = chatContextService.trimHistory(context.getMessageHistory(), 0);
        context.setMessageHistory(new ArrayList<>(trimmedHistory));
        log.info("ReAct链路-历史裁剪完成 | sessionId:{} | before:{} | after:{} | removed:{} | durationMs:{}",
                context.getChatSessionId(), historySizeBeforeTrim, trimmedHistory.size(),
                Math.max(0, historySizeBeforeTrim - trimmedHistory.size()), elapsedMillis(trimStartNanos));
        log.debug("上下文日志-📚 本次投影到 ADK 的历史 | sessionId:{} | messages:{}",
                context.getChatSessionId(), objectMapper.writeValueAsString(trimmedHistory));
        // 同步覆盖 ADK 临时 Session：只投影裁剪后的历史和本次工具所需的终端会话 ID。
        prepareAdkInvocation(runner, context, trimmedHistory);

        // 动态 Prompt 只增强“本次用户消息”；原始消息仍保存在业务历史中，便于后续业务分析。
        long promptStartNanos = System.nanoTime();
        String enrichedMessage = buildEnrichedMessage(userMessage, context);
        log.info("ReAct链路-动态 Prompt 构建完成 | sessionId:{} | originalLength:{} | "
                        + "enrichedLength:{} | addedLength:{} | durationMs:{}",
                context.getChatSessionId(), safeLength(userMessage), safeLength(enrichedMessage),
                Math.max(0, safeLength(enrichedMessage) - safeLength(userMessage)), elapsedMillis(promptStartNanos));
        Content userContent = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(enrichedMessage)))
                .build();
        log.debug("上下文日志-📝 本次 ADK 当前用户消息 | sessionId:{} | userContent:{}",
                context.getChatSessionId(), userContent.toJson());
        // maxLlmCalls 限制的是 ADK 内部真实模型调用次数，而不是外层 Node 的执行次数。
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .maxLlmCalls(context.getMaxLlmCalls())
                .build();
        log.debug("ReAct链路-RunConfig 构建完成 | sessionId:{} | streamingMode:{} | maxLlmCalls:{}",
                context.getChatSessionId(), RunConfig.StreamingMode.SSE, context.getMaxLlmCalls());
        ResponseBodyEmitter emitter = context.getEmitter();
        // fullText 用于 SSE 累计正文和最终 DTO；assistantSegment 只保存尚未写入历史的连续文本段。
        StringBuilder fullText = new StringBuilder();
        StringBuilder assistantSegment = new StringBuilder();
        boolean hasError = false;
        int eventCount = 0;

        log.info("ADK invocation 开始 sessionId={}, userId={}, terminalSessionId={}, trimmedHistory={}, maxLlmCalls={}",
                context.getChatSessionId(), context.getUserId(), context.getTerminalSessionId(),
                trimmedHistory.size(), context.getMaxLlmCalls());
        try {
            Iterator<Event> events = runner.runAsync(context.getUserId(),
                    context.getChatSessionId(), userContent, runConfig).blockingIterable().iterator();

            while (events.hasNext()) {
                Event event = events.next();
                ensureNotCancelled(context);
                eventCount++;

                int functionCallCount = event.functionCalls().size();
                int functionResponseCount = event.functionResponses().size();
                log.debug("ReAct链路-收到 ADK Event | sessionId:{} | sequence:{} | eventId:{} | "
                                + "author:{} | partial:{} | functionCalls:{} | functionResponses:{} | hasContent:{}",
                        context.getChatSessionId(), eventCount, event.id(), event.author(),
                        event.partial().orElse(false), functionCallCount, functionResponseCount,
                        event.content().isPresent());

                // 只转发 assistant/model 的纯文本 Part，FunctionCall/Response 由下方独立处理。
                String eventText = extractAssistantText(event);
                if (!eventText.isBlank()) {
                    fullText.append(eventText);
                    assistantSegment.append(eventText);
                    log.debug("ReAct链路-处理 assistant 文本增量 | sessionId:{} | sequence:{} | "
                                    + "chunkLength:{} | accumulatedLength:{}",
                            context.getChatSessionId(), eventCount, eventText.length(), fullText.length());
                    if (!sendTextEvent(emitter, eventText, fullText.toString())) {
                        context.getCancelled().set(true);
                        log.warn("ReAct链路-文本 SSE 发送失败，标记取消 | sessionId:{} | sequence:{}",
                                context.getChatSessionId(), eventCount);
                        ensureNotCancelled(context);
                    }
                }

                // FunctionCall 表示 ADK 已决定并开始执行工具；这里只记录和通知前端，不执行工具。
                List<Map<String, Object>> historyCalls = handleFunctionCalls(event.functionCalls(), context, emitter);
                if (!historyCalls.isEmpty()) {
                    // 工具调用是消息边界，先提交调用前的 assistant 文本，保证历史时序正确。
                    flushAssistantSegment(context, assistantSegment);
                    Map<String, Object> assistantToolCall = new HashMap<>();
                    assistantToolCall.put("role", "assistant");
                    assistantToolCall.put("content", "");
                    assistantToolCall.put("tool_calls", historyCalls);
                    context.appendMessage(assistantToolCall);
                    log.debug("ReAct链路-assistant.tool_calls 已写入业务历史 | sessionId:{} | "
                                    + "calls:{} | historySize:{}",
                            context.getChatSessionId(), historyCalls.size(), context.getMessageHistory().size());
                }

                if (!event.functionResponses().isEmpty()) {
                    // FunctionResponse 是 ADK 内部真实工具执行结果，以 toolCallId 与调用关联。
                    flushAssistantSegment(context, assistantSegment);
                    handleFunctionResponses(event.functionResponses(), context, emitter);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            context.getCancelled().set(true);
            context.setStopReason(StopReasonEnum.USER_STOP.getCode());
            log.info("ReAct链路-ADK invocation 已取消 | sessionId:{} | processedEvents:{} | "
                            + "textLength:{} | toolCalls:{} | durationMs:{}",
                    context.getChatSessionId(), eventCount, fullText.length(),
                    context.getCurrentToolCalls().size(), elapsedMillis(nodeStartNanos));
        } catch (Exception exception) {
            hasError = true;
            String errorMessage = "ADK Runner error: " + safeMessage(exception);
            context.setErrorMessage(errorMessage);
            context.setStopReason(StopReasonEnum.ERROR.getCode());
            sendErrorEvent(emitter, errorMessage);
            log.error("ReAct链路-ADK invocation 失败 | sessionId:{} | processedEvents:{} | "
                            + "textLength:{} | toolCalls:{} | toolResults:{} | durationMs:{}",
                    context.getChatSessionId(), eventCount, fullText.length(),
                    context.getCurrentToolCalls().size(), context.getCurrentToolResults().size(),
                    elapsedMillis(nodeStartNanos), exception);
        } finally {
            // 无论正常、取消还是异常，都保留已收到的文本，避免流式中途失败导致历史丢失。
            flushAssistantSegment(context, assistantSegment);
            context.appendAssistantContent(fullText.toString());
        }

        // 外层 step 表示一次完整 ADK invocation；内部发生多少次 LLM/工具调用由 ADK 管理。
        context.incrementStep();
        context.getResult().setTotalSteps(context.getStep());
        boolean roundEndSent = sendRoundEndEvent(emitter, context.getStep(), context.getMaxSteps(), false,
                context.getTotalToolCallCount().get());
        log.info("ReAct链路-AiCallNode 完成 | sessionId:{} | events:{} | toolCalls:{} | "
                        + "toolResults:{} | textLength:{} | historySize:{} | roundEndSent:{} | "
                        + "stopReason:{} | error:{} | durationMs:{}",
                context.getChatSessionId(), eventCount, context.getCurrentToolCalls().size(),
                context.getCurrentToolResults().size(), fullText.length(), context.getMessageHistory().size(),
                roundEndSent, context.getStopReason(), hasError, elapsedMillis(nodeStartNanos));
        return router(request, context);
    }

    private void prepareAdkInvocation(Runner runner, DefaultReActFactory.DynamicContext context,
                                      List<Map<String, Object>> priorHistory) {
        long startNanos = System.nanoTime();
        log.info("ReAct链路-准备 ADK Session 投影 | sessionId:{} | appName:{} | historySize:{} | "
                        + "terminalSessionIdPresent:{}",
                context.getChatSessionId(), runner.appName(), sizeOf(priorHistory),
                context.getTerminalSessionId() != null && !context.getTerminalSessionId().isBlank());
        // 使用其他 SessionService 会重新引入 ADK 自维护历史，与业务历史形成双重事实来源。
        if (!(runner.sessionService() instanceof CustomAdkSessionService sessionService)) {
            log.error("ReAct链路-ADK SessionService 类型错误 | sessionId:{} | actualType:{}",
                    context.getChatSessionId(), runner.sessionService().getClass().getName());
            throw new IllegalStateException("Runner must use CustomAdkSessionService for business-managed history");
        }
        sessionService.prepareInvocation(
                runner.appName(), context.getUserId(), context.getChatSessionId(),
                priorHistory, context.getTerminalSessionId());
        log.info("ReAct链路-ADK Session 投影完成 | sessionId:{} | projectedMessages:{} | durationMs:{}",
                context.getChatSessionId(), sizeOf(priorHistory), elapsedMillis(startNanos));
    }



    private List<Map<String, Object>> handleFunctionCalls(
            List<FunctionCall> calls, DefaultReActFactory.DynamicContext context,
            ResponseBodyEmitter emitter) throws Exception {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        log.info("ReAct链路-开始处理 FunctionCall | sessionId:{} | received:{} | existing:{}",
                context.getChatSessionId(), calls.size(), context.getCurrentToolCalls().size());
        // 返回值专供 messageHistory 构造 assistant.tool_calls；DTO 列表用于结果与前端事件。
        List<Map<String, Object>> historyCalls = new ArrayList<>();
        for (FunctionCall call : calls) {
            String id = call.id().filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException("Function call ID is missing"));
            String name = call.name()
                    .orElseThrow(() -> new IllegalStateException("Function call name is missing"));
            String args = objectMapper.writeValueAsString(call.args().orElse(Map.of()));
            // 流式供应商可能重复携带同一个完整 FunctionCall，按 ID 保证幂等。
            if (context.getCurrentToolCalls().stream().anyMatch(existing -> id.equals(existing.id()))) {
                log.debug("忽略重复工具调用事件 id={}, name={}", id, name);
                continue;
            }

            ToolCallDTO toolCall = new ToolCallDTO(id, name, args);
            context.getCurrentToolCalls().add(toolCall);
            context.getExecutedToolCalls().add(toolCall);
            context.incrementRoundToolCalls();
            context.incrementTotalToolCalls();
            boolean toolCallSent = sendToolCallEvent(emitter, id, name, args, ToolStatusEnum.RUNNING);

            Map<String, Object> historyCall = new HashMap<>();
            historyCall.put("id", id);
            historyCall.put("name", name);
            historyCall.put("args", args);
            historyCalls.add(historyCall);
            log.info("ReAct链路-工具调用已登记 | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "argsLength:{} | roundToolCalls:{} | totalToolCalls:{} | sseSent:{}",
                    context.getChatSessionId(), id, name, args.length(),
                    context.getRoundToolCallCount().get(), context.getTotalToolCallCount().get(), toolCallSent);
        }
        log.info("ReAct链路-FunctionCall 处理完成 | sessionId:{} | newCalls:{} | currentTotal:{}",
                context.getChatSessionId(), historyCalls.size(), context.getCurrentToolCalls().size());
        return historyCalls;
    }

    private void handleFunctionResponses(
            List<FunctionResponse> responses, DefaultReActFactory.DynamicContext context,
            ResponseBodyEmitter emitter) {
        log.info("ReAct链路-开始处理 FunctionResponse | sessionId:{} | received:{} | existing:{}",
                context.getChatSessionId(), sizeOf(responses), context.getCurrentToolResults().size());
        int accepted = 0;
        for (FunctionResponse response : responses) {
            String id = response.id().filter(value -> !value.isBlank()).orElse("");
            String name = response.name().orElse("");
            if (id.isBlank()) {
                log.warn("忽略缺少 ID 的工具结果 sessionId={}, name={}", context.getChatSessionId(), name);
                continue;
            }
            if (context.getCurrentToolResults().stream().anyMatch(existing -> id.equals(existing.id()))) {
                log.debug("忽略重复工具结果事件 id={}, name={}", id, name);
                continue;
            }

            // SshExecuteAdkTool 的稳定协议为 output/command/success；缺少 success 时按失败处理。
            Map<String, Object> result = response.response().orElse(Map.of());
            String output = String.valueOf(result.getOrDefault("output", ""));
            String command = String.valueOf(result.getOrDefault("command", ""));
            boolean success = Boolean.TRUE.equals(result.get("success"));
            ToolStatusEnum status = success ? ToolStatusEnum.SUCCESS : ToolStatusEnum.ERROR;
            context.getCurrentToolResults().add(new ToolResultDTO(id, name, output, command, status.getCode()));
            accepted++;
            // tool 消息必须紧跟对应 assistant.tool_calls，下一次业务请求才能还原完整工具上下文。
            context.appendToolMessage(id, output);
            boolean toolResultSent = sendToolResultEvent(emitter, id, output, status);
            if ("executeCommand".equals(name) && !command.isBlank()) {
                context.addRecentCommand(truncate(command, 256));
            }
            log.info("ReAct链路-工具结果已登记 | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "status:{} | commandLength:{} | outputLength:{} | historySize:{} | sseSent:{}",
                    context.getChatSessionId(), id, name, status.getCode(), command.length(), output.length(),
                    context.getMessageHistory().size(), toolResultSent);
        }
        log.info("ReAct链路-FunctionResponse 处理完成 | sessionId:{} | accepted:{} | currentTotal:{}",
                context.getChatSessionId(), accepted, context.getCurrentToolResults().size());
    }

    private String extractAssistantText(Event event) {
        if (event.content().isEmpty()) {
            return "";
        }
        Content content = event.content().get();
        if (!AgentUtils.isAssistant(content.role().orElse(""))) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Part part : content.parts().orElse(List.of())) {
            part.text().ifPresent(result::append);
        }
        return result.toString();
    }

    private void flushAssistantSegment(DefaultReActFactory.DynamicContext context, StringBuilder segment) {
        // 分段落历史而不是最终一次性追加，目的是保留文本与工具事件的相对位置。
        if (!segment.isEmpty()) {
            int segmentLength = segment.length();
            context.appendAssistantMessage(segment.toString());
            segment.setLength(0);
            log.debug("ReAct链路-assistant 文本段已写入业务历史 | sessionId:{} | segmentLength:{} | "
                            + "historySize:{}",
                    context.getChatSessionId(), segmentLength, context.getMessageHistory().size());
        }
    }

    private void ensureNotCancelled(DefaultReActFactory.DynamicContext context) throws InterruptedException {
        // emitter 回调设置 cancelled 并中断 Future；两种信号都要识别，降低断连后的继续消耗。
        if (context.getCancelled().get() || Thread.currentThread().isInterrupted()) {
            log.info("ReAct链路-检测到取消信号 | sessionId:{} | contextCancelled:{} | threadInterrupted:{}",
                    context.getChatSessionId(), context.getCancelled().get(), Thread.currentThread().isInterrupted());
            throw new InterruptedException("SSE client disconnected");
        }
    }

    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext context) {
        log.debug("ReAct链路-调用里程碑识别 | sessionId:{} | role:user | contentLength:{}",
                context.getChatSessionId(), safeLength(userMessage));
        promptService.detectAndRecordMilestone(context.getChatSessionId(), "user", userMessage);
        log.debug("ReAct链路-调用动态 Prompt 构建 | sessionId:{} | recentCommands:{} | historySize:{}",
                context.getChatSessionId(), sizeOf(context.getRecentCommands()), sizeOf(context.getMessageHistory()));
        String enrichedMessage = promptService.buildEnrichedMessage(
                userMessage, context.getChatSessionId(), context.getUserId(),
                context.getTerminalSessionId(), context.getRecentCommands(), context.getMessageHistory());
        log.debug("ReAct链路-动态 Prompt 服务返回 | sessionId:{} | contentLength:{}",
                context.getChatSessionId(), safeLength(enrichedMessage));
        return enrichedMessage;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequest request, DefaultReActFactory.DynamicContext context) {
        // ToolCallNode 只负责核对/归档已执行结果；没有工具事件时直接进入结束条件判断。
        String nextBean = context.getCurrentToolCalls().isEmpty()
                ? "reactLoopDecisionNode"
                : "reactToolCallNode";
        log.info("ReAct链路-AiCallNode 路由 | sessionId:{} | nextNode:{} | toolCalls:{} | toolResults:{}",
                context.getChatSessionId(), nextBean, context.getCurrentToolCalls().size(),
                context.getCurrentToolResults().size());
        return getBean(nextBean);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
