package com.jasonlat.ai.cases.react.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jasonlat.ai.cases.react.AbstractAIAgentReActSupport;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.ToolResultReconciler;
import com.jasonlat.ai.cases.react.model.valobj.StopReasonEnum;
import com.jasonlat.ai.domain.agent.service.IChatContextService;
import com.jasonlat.ai.domain.agent.service.ILongTermMemoryService;
import com.jasonlat.ai.domain.agent.service.IPromptService;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import com.jasonlat.ai.trigger.api.dto.ToolCallDTO;
import com.jasonlat.ai.trigger.api.dto.ToolResultDTO;
import com.jasonlat.ai.trigger.api.dto.enums.ToolStatusEnum;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ADK 工具事件核对与业务归档节点。
 *
 * <p>真实执行只允许发生在 ADK 内部 ReAct 循环。本节点按 toolCallId 核对调用与结果、
 * 更新里程碑和工具摘要；缺失结果会生成明确的错误结果，但绝不会补跑命令。
 * 这样可以避免非幂等 SSH 命令因事件丢失而被执行第二次。</p>
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private IPromptService promptService;
    @Resource
    private IChatContextService chatContextService;
    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequest request, DefaultReActFactory.DynamicContext context) throws Exception {

        long nodeStartNanos = System.nanoTime();

        List<ToolCallDTO> calls = context.getCurrentToolCalls();
        List<ToolResultDTO> results = context.getCurrentToolResults();

        log.info("ReAct链路-ToolCallNode 开始 | sessionId:{} | calls:{} | results:{} | "
                        + "roundToolCalls:{} | totalToolCalls:{}",
                context.getChatSessionId(), sizeOf(calls), sizeOf(results),
                context.getRoundToolCallCount().get(), context.getTotalToolCallCount().get());

        // 不能依赖数组下标：并行/多工具事件可能乱序，必须使用 ADK toolCallId 关联。
        ToolResultReconciler.Reconciliation reconciliation =
                ToolResultReconciler.reconcile(calls, results);
        log.info("ReAct链路-工具调用结果核对完成 | sessionId:{} | matched:{} | missing:{}",
                context.getChatSessionId(), reconciliation.matched().size(), reconciliation.missing().size());

        // 已匹配结果只做业务派生：记录里程碑并刷新供后续 Prompt 使用的工具摘要。
        for (ToolResultReconciler.MatchedTool matched : reconciliation.matched()) {
            ToolCallDTO call = matched.call();
            ToolResultDTO result = matched.result();
            String command = resolveCommand(call.args());
            log.debug("ReAct链路-调用工具里程碑识别 | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "resultLength:{}",
                    context.getChatSessionId(), call.id(), call.name(), safeLength(result.content()));
            promptService.detectAndRecordMilestone(context.getChatSessionId(), "tool", result.content());

            log.debug("ReAct链路-调用工具摘要归档 | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "commandLength:{} | resultLength:{}",
                    context.getChatSessionId(), call.id(), call.name(), command.length(),
                    safeLength(result.content()));
            chatContextService.pushToolResult(
                    context.getChatSessionId(), call.name(), command, result.content());

            log.info("ADK 工具结果已归档 sessionId={}, id={}, name={}, status={}, outputLength={}",
                    context.getChatSessionId(), call.id(), call.name(), result.status(),
                    result.content() == null ? 0 : result.content().length());

            longTermMemoryService.saveToolMessage(
                    context.getUserId(),
                    context.getChatSessionId(),
                    call.name(),
                    call.id(),
                    result.content(),
                    !isFailureContent(result.content())
            );
        }

        /*
         * FunctionCall 已出现但没有 FunctionResponse，说明 ADK 事件链不完整。
         * 此处合成失败结果用于前端和历史闭合，但绝不调用工具进行“补偿执行”。
         */
        for (ToolCallDTO call : reconciliation.missing()) {
            String command = resolveCommand(call.args());
            String error = "ADK 未返回工具结果: " + call.name();

            context.getCurrentToolResults().add(new ToolResultDTO(
                    call.id(), call.name(), error, command, ToolStatusEnum.ERROR.getCode()));
            boolean resultSent = sendToolResultEvent(context.getEmitter(), call.id(), error, ToolStatusEnum.ERROR);

            promptService.detectAndRecordMilestone(context.getChatSessionId(), "tool", error);
            chatContextService.pushToolResult(context.getChatSessionId(), call.name(), command, error);

            // 工具结果落库 + 长期记忆提取：委托领域服务完成"消息落库（role=tool, priority=HIGH）
            // + 环境/软件/失败信号记忆提取"闭环，case 层不再直接调用仓储层。
            longTermMemoryService.saveToolMessage(
                    context.getUserId(),
                    context.getChatSessionId(),
                    call.name(),
                    call.id(),
                    error,
                    false
            );


            log.error("ReAct链路-ADK 工具结果缺失 | sessionId:{} | toolCallId:{} | toolName:{} | "
                            + "syntheticResultSent:{}",
                    context.getChatSessionId(), call.id(), call.name(), resultSent);
        }

        log.info("ReAct链路-ToolCallNode 完成 | sessionId:{} | matched:{} | missing:{} | "
                        + "toolResults:{} | durationMs:{}",
                context.getChatSessionId(), reconciliation.matched().size(), reconciliation.missing().size(),
                context.getCurrentToolResults().size(), elapsedMillis(nodeStartNanos));

        return router(request, context);
    }

    private String resolveCommand(String argsJson) {
        // 命令来自 FunctionCall 参数，不从工具输出反推；解析失败时保留原始参数方便排障。
        if (argsJson == null || argsJson.isBlank()) {
            log.debug("ReAct链路-工具参数为空，无法提取命令");
            return "";
        }
        try {
            Map<String, Object> args = objectMapper.readValue(
                    argsJson, new TypeReference<Map<String, Object>>() { });
            String command = String.valueOf(args.getOrDefault("command", ""));
            log.debug("ReAct链路-工具命令参数解析完成 | argsLength:{} | commandLength:{}",
                    argsJson.length(), command.length());
            return command;
        } catch (Exception exception) {
            log.warn("工具参数解析失败 args={}", argsJson, exception);
            return argsJson;
        }
    }

    @Override
    public StrategyHandler<ChatRequest, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequest request, DefaultReActFactory.DynamicContext context) {
        log.info("ReAct链路-ToolCallNode 路由 | sessionId:{} | nextNode:LoopDecisionNode",
                context.getChatSessionId());
        return getBean("reactLoopDecisionNode");
    }

    /**
     * 判断工具输出内容是否包含失败特征（error、failed、permission denied 等）。
     * <p>
     * 用于长期记忆提取时判断 success 参数：如果工具执行状态为 success 但内容包含失败信号，
     * 也会被 recordToolObservation 记录为 TROUBLESHOOTING_CASE。
     *
     * @param content 工具执行输出内容
     * @return true 表示内容包含失败特征
     */
    private boolean isFailureContent(String content) {
        if (content == null) {
            return false;
        }
        String normalized = content.toLowerCase();
        return normalized.contains("error")
                || normalized.contains("failed")
                || normalized.contains("not found")
                || normalized.contains("no such")
                || normalized.contains("permission denied")
                || normalized.contains("connection refused");
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
