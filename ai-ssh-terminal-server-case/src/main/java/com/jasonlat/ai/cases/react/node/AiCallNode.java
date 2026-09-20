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
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.TaskStateVO;
import com.jasonlat.ai.domain.agent.service.IChatContextService;
import com.jasonlat.ai.domain.agent.service.IIntentService;
import com.jasonlat.ai.domain.agent.service.ILongTermMemoryService;
import com.jasonlat.ai.domain.agent.service.IPromptService;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import com.jasonlat.ai.domain.agent.service.intent.IntentService;
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
    @Resource
    private IIntentService intentService;
    @Resource
    private ILongTermMemoryService longTermMemoryService;

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
        String userMessage = getLastUserMessage(request, context);
        // 当前 user 原文写入业务历史 必须放在 prepareAdkInvocation 之后，避免当前消息被同时作为历史和 runAsync 参数发送两次
        context.appendUserMessage(userMessage);

        // 清空的是本次请求的输出缓冲，不清空 RootNode 刚加载的跨请求历史。
        context.resetRoundBuffers();
        context.resetRoundToolCalls();
        context.setStopReason(null);
        context.setErrorMessage(null);
        log.debug("ReAct链路-本轮缓冲已重置 | sessionId:{} | currentToolCalls:{} | "
                        + "currentToolResults:{} | roundToolCalls:{}",
                context.getChatSessionId(), context.getCurrentToolCalls().size(),
                context.getCurrentToolResults().size(), context.getRoundToolCallCount().get());

        // [Phase 3] 意图识别 —— 注入当前 Agent 的 API 配置后，再识别用户意图
        // 复用智能体自己的模型配置，不单独配置意图识别模型
        // 步骤：①configure 注入 API → ②classify 识别 → ③存入上下文 → ④不硬路由
        IntentRequestVO.IntentRequestVOBuilder intentRequestVOBuilder = IntentRequestVO.builder()
                .userId(context.getUserId())
                .chatSessionId(context.getChatSessionId())
                .userMessage(userMessage);
        if (registration.getOpenAiApi() != null) {
            intentRequestVOBuilder.llmIntentOpenAiApi(registration.getOpenAiApi());
            intentRequestVOBuilder.llmIntentModelName(registration.getChatModelName());
        }
        IntentResultVO intentResult = intentService.classify(intentRequestVOBuilder.build());
        log.info("识别到用户意图: {}, 置信度: {}, 候选: {}, 重分类: {}",
                intentResult.getIntent().getLabel(),
                intentResult.getConfidence(),
                intentResult.getCandidateIntents(),
                intentResult.isReclassified());

        // 将意图保存到上下文供后续使用
        context.setCurrentIntent(intentResult.getIntent().name());
        context.setCurrentIntentResult(intentResult);
        // 分类后同步任务态：处理 CONTINUE 续接、非业务意图跳过、新任务创建/覆盖。
        syncTaskStateAfterClassification(context, userMessage, intentResult);

        // COMPOUND / UNKNOWN / 低置信度：交给主模型自行判断，不再硬路由
        if (intentResult.getIntent().equals(IntentTypeEnumVO.COMPOUND)) {
            log.info("复合意图，候选 {} —— 交由主模型拆解", intentResult.getCandidateIntents());
        } else if (intentResult.getIntent().equals(IntentTypeEnumVO.UNKNOWN) || intentResult.getConfidence() < 0.5) {
            log.info("意图不确定 ({}，conf={}) —— 全交主模型决策", intentResult.getIntent(), intentResult.getConfidence());
        }

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

        // 同步覆盖 ADK 临时 Session：只投影裁剪后的历史和本次工具所需的终端会话 ID。
        prepareAdkInvocation(runner, context, trimmedHistory);
        log.debug("上下文日志-📚 本次投影到 ADK 的历史 | sessionId:{} | messages:{}",
                context.getChatSessionId(), objectMapper.writeValueAsString(trimmedHistory));

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

        // 用户消息落库 + 长期记忆提取（用户侧）：委托领域服务完成"消息落库 + 偏好记忆提取"闭环，
        // case 层不再直接调用仓储层。仅首轮（step==0）落库 user 消息，避免多轮循环重复写入。
        longTermMemoryService.saveUserMessage(
                context.getUserId(),
                context.getChatSessionId(),
                userMessage,
                context.getCurrentIntent(),
                context.getStep() == 0
        );


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

            for (Event event : runner.runAsync(context.getUserId(),
                    context.getChatSessionId(), userContent, runConfig).blockingIterable()) {
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

        if (!fullText.isEmpty()) {
            // 助手回复落库 + 结论记忆提取：委托领域服务完成闭环。
            longTermMemoryService.saveAssistantMessage(
                    context.getUserId(),
                    context.getChatSessionId(),
                    fullText.toString()
            );
        }

        boolean roundEndSent = sendRoundEndEvent(emitter, context.getStep(), context.getMaxSteps(), context.getTotalToolCallCount().get());

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
            log.info("ReAct链路-工具结果已登记到 DynamicContext | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "status:{} | commandLength:{} | outputLength:{} | historySize:{} | sseSent:{}",
                    context.getChatSessionId(), id, name, status.getCode(), command.length(), output.length(),
                    context.getMessageHistory().size(), toolResultSent);

            // 反馈回路：根据工具结果判定当前意图是否走偏，必要时重分类
            handleIntentFeedback(context, output);
        }
        log.info("ReAct链路-FunctionResponse 处理完成 | sessionId:{} | accepted:{} | currentTotal:{}",
                context.getChatSessionId(), accepted, context.getCurrentToolResults().size());
    }

    /**
     * 获取最新的一条用户消息
     */
    private String getLastUserMessage(ChatRequest requestParameter, DefaultReActFactory.DynamicContext dynamicContext) {
        // 第一轮使用请求参数中的消息
        if (dynamicContext.getStep() == 0) {
            return requestParameter.getMessage();
        }

        // 后续轮次从历史记录中获取最后一条 user 消息
        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return requestParameter.getMessage();
    }

    /**
     * 反馈回路：根据工具执行结果判定当前意图是否需要重分类。
     * <p>
     * 仅在本轮已有意图识别结果时触发；reportFeedback 返回非 null 表示已重分类，
     * 此时更新 DynamicContext 的当前意图，使后续步骤（Prompt 注入、路由）使用新意图。
     * <p>
     * 流程：
     * <pre>
     *   工具执行完(result)
     *     ├─ 无本轮意图结果 → 直接返回
     *     ├─ 判定 success（非空 && 不像意图走偏）
     *     └─ intentService.reportFeedback(...)
     *          ├─ 返回 null  → 维持原意图
     *          └─ 返回新结果 → 更新 currentIntent / currentIntentResult
     * </pre>
     * 案例：意图 CONFIGURE，工具结果 "No such file" → success=false →
     *       reportFeedback 用候选 MONITOR 递补 → 后续 Prompt 注入 [用户意图] 监控查看。
     */
    private void handleIntentFeedback(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        IntentResultVO lastIntent = dynamicContext.getCurrentIntentResult();
        if (lastIntent == null) {
            return;
        }
        // 复用 IntentService 的失败特征判定，保持两处逻辑一致
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);

        IntentResultVO reclassified = intentService.reportFeedback(
                dynamicContext.getChatSessionId(), lastIntent, success, toolResult);
        if (reclassified != null) {
            log.info("反馈回路触发重分类: {} -> {} (conf={})",
                    lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());

            dynamicContext.setCurrentIntent(reclassified.getIntent().name());
            dynamicContext.setCurrentIntentResult(reclassified);

            updateTaskStateAfterFeedback(dynamicContext, success, reclassified);
        }
    }

    /**
     * 分类后同步任务态：处理 CONTINUE 续接、非业务意图跳过、新任务创建/覆盖。
     * <p>
     * 策略：
     * <ul>
     *   <li>CONTINUE：如有进行中任务态则校准步骤索引、清除失败标记，并回写 currentIntent 为根意图</li>
     *   <li>UNKNOWN/CHAT：不维护任务态，直接返回</li>
     *   <li>其他业务意图：无任务态/已完成/意图变更 → 创建新 TaskStateVO；否则复用并刷新</li>
     * </ul>
     *
     * @param lastUserMessage 最新用户消息（作为任务描述）
     * @param intentResult   本轮意图识别结果
     */
    private void syncTaskStateAfterClassification(DefaultReActFactory.DynamicContext dynamicContext,
                                                  String lastUserMessage, IntentResultVO intentResult) {
        if (intentResult == null) {
            return;
        }

        String sessionId = dynamicContext.getChatSessionId();
        IntentTypeEnumVO intent = intentResult.getIntent();
        TaskStateVO taskState = intentService.getTaskState(sessionId);

        if (intent.equals(IntentTypeEnumVO.CONTINUE)) {
            if (taskState != null && !taskState.isCompleted()) {
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                taskState.setLastFeedbackFailed(false);
                intentService.updateTaskState(sessionId, taskState);
                if (taskState.getRootIntent() != null) {
                    dynamicContext.setCurrentIntent(taskState.getRootIntent().name());
                }
            }
            return;
        }

        if (intent == IntentTypeEnumVO.UNKNOWN || intent == IntentTypeEnumVO.CHAT) {
            return;
        }

        boolean shouldReplace = taskState == null
                || taskState.isCompleted()
                || taskState.getRootIntent() != intent;

        if (shouldReplace) {
            List<String> steps = new ArrayList<>();
            steps.add(lastUserMessage);
            taskState = TaskStateVO.builder()
                    .taskDescription(lastUserMessage)
                    .rootIntent(intent)
                    .steps(steps)
                    .currentStepIndex(0)
                    .completed(false)
                    .lastFeedbackFailed(false)
                    .build();
        } else {
            if (taskState.getTaskDescription() == null || taskState.getTaskDescription().isBlank()) {
                taskState.setTaskDescription(lastUserMessage);
            }
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(lastUserMessage)));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            taskState.setCompleted(false);
            taskState.setLastFeedbackFailed(false);
        }

        intentService.updateTaskState(sessionId, taskState);
    }

    /**
     * 反馈后更新任务态：成功推进步骤索引，失败标记 lastFeedbackFailed，
     * 重分类时替换 rootIntent。
     * <p>
     * 流程：
     * <pre>
     *   工具反馈回调
     *     ├─ 无任务态 → 直接返回
     *     ├─ reclassified 非 null 且非兜底意图 → 替换 rootIntent
     *     ├─ success=true → currentStepIndex++ 或标记 completed
     *     └─ success=false → lastFeedbackFailed=true
     * </pre>
     *
     * @param success      本轮工具执行是否成功
     * @param reclassified  反馈回路重分类结果，可为 null
     */
    private void updateTaskStateAfterFeedback(DefaultReActFactory.DynamicContext dynamicContext,
                                              boolean success, IntentResultVO reclassified) {
        TaskStateVO taskState = intentService.getTaskState(dynamicContext.getChatSessionId());
        if (taskState == null) {
            return;
        }

        if (reclassified != null
                && reclassified.getIntent() != null
                && reclassified.getIntent() != IntentTypeEnumVO.UNKNOWN
                && reclassified.getIntent() != IntentTypeEnumVO.CONTINUE
                && reclassified.getIntent() != IntentTypeEnumVO.CHAT) {
            taskState.setRootIntent(reclassified.getIntent());
        }

        taskState.setLastFeedbackFailed(!success);

        if (success) {
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(taskState.getTaskDescription())));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            if (taskState.getCurrentStepIndex() >= taskState.getSteps().size() - 1) {
                taskState.setCompleted(true);
            } else {
                taskState.setCurrentStepIndex(taskState.getCurrentStepIndex() + 1);
            }
        }

        intentService.updateTaskState(dynamicContext.getChatSessionId(), taskState);
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

    /**
     * 构建注入了动态上下文的用户消息
     * 委托 IPromptService 完成环境采集、里程碑获取、前缀构建
     * <p>
     * 意图注入路径：dynamicContext.currentIntent → buildEnrichedMessage(intentLabel)
     * → PromptContextVO.intentLabel → DynamicPromptBuilder 输出 "[用户意图] xxx" 前缀，
     * 让主模型感知当前意图但不强制路由。
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext context) {
        log.debug("ReAct链路-调用里程碑识别 | sessionId:{} | role:user | contentLength:{}", context.getChatSessionId(), safeLength(userMessage));

        // 构建带动态上下文前缀的富化消息
        promptService.detectAndRecordMilestone(context.getChatSessionId(), "user", userMessage);
        log.debug("ReAct链路-调用动态 Prompt 构建 | sessionId:{} | recentCommands:{} | historySize:{}",
                context.getChatSessionId(), sizeOf(context.getRecentCommands()), sizeOf(context.getMessageHistory()));

        // 构建注入了动态上下文的用户消息
        String enrichedMessage = promptService.buildEnrichedMessage(
                userMessage, context.getChatSessionId(), context.getUserId(),
                context.getTerminalSessionId(), context.getRecentCommands(),
                context.getMessageHistory(), context.getCurrentIntent());
        log.debug("ReAct链路-动态 Prompt 服务返回 | sessionId:{} | enrichedUserMessage:{}",
                context.getChatSessionId(), truncate(enrichedMessage, 256));

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
