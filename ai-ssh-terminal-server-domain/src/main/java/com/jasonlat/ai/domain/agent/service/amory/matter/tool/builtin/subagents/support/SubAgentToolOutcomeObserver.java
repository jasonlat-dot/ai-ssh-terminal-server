package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort.ToolOutcome;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort.ToolOutcomeDecisionRequest;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 Agent 工具结果的 System One 观察器工厂。
 *
 * <p>主 Agent 工具结果由 AiCallNode 和 IntentService 处理；子 Agent 使用独立 Runner，
 * 不经过主 Agent 的 AiCallNode，因此需要在子 Runner 的事件流中独立观察 FunctionCall
 * 和 FunctionResponse。本组件只执行工具结果判断，不执行新的用户意图分类，
 * 也不会修改主 Agent 已识别的用户意图。</p>
 *
 * <p>一个子 Agent 运行实例必须创建一个独立 {@link ObservationSession}。
 * FunctionCall 参数、缺失响应 ID 的顺序配对以及结果去重状态不能跨并发任务共享。</p>
 */
@Slf4j
@Component
public class SubAgentToolOutcomeObserver {

    /**
     * Jev/Laya 工具结果结构化判断端口。
     */
    private final IToolOutcomeDecisionPort toolOutcomeDecisionPort;

    /**
     * 用于把 Map、List 等工具输入和结果稳定序列化为 JSON。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建子 Agent 工具结果观察器工厂。
     *
     * @param toolOutcomeDecisionPort System One 工具结果判断端口
     * @param objectMapper            Spring Boot 统一管理的 JSON 序列化器
     */
    public SubAgentToolOutcomeObserver(
            IToolOutcomeDecisionPort toolOutcomeDecisionPort,
            ObjectMapper objectMapper
    ) {
        this.toolOutcomeDecisionPort = toolOutcomeDecisionPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一次子 Agent 运行创建独立观察会话。
     *
     * @param sourceAgent      当前子 Agent 运行时名称
     * @param delegatedRequest 主 Agent 下发给该子 Agent 的任务描述
     * @param invocation       子 Agent 调用上下文，携带主 Agent 当前意图快照
     * @return 仅供本次子 Agent Runner 使用的事件观察会话
     */
    public ObservationSession openSession(
            String sourceAgent,
            String delegatedRequest,
            AgentInvocationContext invocation
    ) {
        IntentTypeEnumVO rootIntent = invocation == null ? null : invocation.rootIntent();
        double rootIntentConfidence = invocation == null ? 0.0 : invocation.rootIntentConfidence();

        return new ObservationSession(
                normalize(sourceAgent),
                normalize(delegatedRequest),
                rootIntent,
                normalizeProbability(rootIntentConfidence));
    }

    /**
     * 单次子 Agent Runner 的有状态事件观察器。
     *
     * <p>ADK 通常把 FunctionCall 和 FunctionResponse 放在不同 Event 中，
     * 因此需要暂存调用参数，等响应到达后再构建完整的 Jev/Laya 请求。</p>
     */
    public final class ObservationSession {

        /** 当前子 Agent 名称，只用于关联日志和工具名称。 */
        private final String sourceAgent;

        /** 子 Agent 当前负责的局部任务，比主用户原文更适合判断内部工具是否偏题。 */
        private final String delegatedRequest;

        /** 主 Agent 已完成识别的用户意图快照；本观察器不会重新分类或修改它。 */
        private final IntentTypeEnumVO rootIntent;

        /** 主 Agent 意图对应的置信度快照。 */
        private final double rootIntentConfidence;

        /** 按工具调用 ID 保存尚未收到响应的调用参数。 */
        private final Map<String, PendingToolCall> pendingById = new HashMap<>();

        /** 响应缺少 ID 时，按照同名工具调用顺序完成配对。 */
        private final Map<String, Deque<String>> pendingIdsByToolName = new HashMap<>();

        /** 已处理响应键，防止流式供应商重复发送完整 FunctionResponse。 */
        private final Set<String> processedResponses = new HashSet<>();

        /** 为缺少 FunctionCall ID 的事件生成当前观察会话内唯一的兜底编号。 */
        private final AtomicInteger missingCallIds = new AtomicInteger();

        /**
         * 创建单次运行观察状态。
         *
         * @param sourceAgent         子 Agent 名称
         * @param delegatedRequest    子 Agent 局部任务
         * @param rootIntent          主 Agent 当前意图
         * @param rootIntentConfidence 主 Agent 当前意图置信度
         */
        private ObservationSession(
                String sourceAgent,
                String delegatedRequest,
                IntentTypeEnumVO rootIntent,
                double rootIntentConfidence
        ) {
            this.sourceAgent = sourceAgent;
            this.delegatedRequest = delegatedRequest;
            this.rootIntent = rootIntent;
            this.rootIntentConfidence = rootIntentConfidence;
        }

        /**
         * 观察子 Runner 产生的一条 ADK Event。
         *
         * <p>同一个 Event 可能同时包含多个 FunctionCall 或 FunctionResponse，
         * 所以必须遍历完整列表。任何判断异常都会在内部降级，不能中断子 Agent。</p>
         *
         * @param event 子 Agent Runner 产生的原始事件
         */
        public void observe(Event event) {
            if (event == null) {
                return;
            }

            try {
                // 先登记调用，再处理响应，兼容同一个 Event 同时携带调用与结果的情况。
                for (FunctionCall call : event.functionCalls()) {
                    rememberCall(event, call);
                }

                for (FunctionResponse response : event.functionResponses()) {
                    assessResponse(event, response);
                }
            } catch (RuntimeException exception) {
                /*
                 * 观察器不能成为子 Runner 的故障源。
                 * 单条 ADK Event 结构异常时跳过该事件，后续事件仍可继续观察。
                 */
                log.warn(
                        "子Agent工具事件观察异常，已忽略 sourceAgent={} eventId={} exception={} message={}",
                        sourceAgent,
                        event.id(),
                        exception.getClass().getSimpleName(),
                        exception.getMessage());

                log.debug("子Agent工具事件观察异常详情", exception);
            }
        }

        /**
         * 暂存一次 FunctionCall 的名称和参数。
         *
         * @param event 承载该调用的 ADK Event，用于生成缺失 ID 的兜底值
         * @param call  ADK 工具调用
         */
        private void rememberCall(Event event, FunctionCall call) {
            String toolName = call.name().orElse("unknown_tool");
            String callId = call.id()
                    .filter(id -> !id.isBlank())
                    .orElseGet(() -> event.id() + "_missing_call_" + missingCallIds.incrementAndGet());
            Map<String, Object> arguments = new LinkedHashMap<>(call.args().orElse(Map.of()));

            pendingById.put(callId, new PendingToolCall(arguments));
            pendingIdsByToolName
                    .computeIfAbsent(toolName, ignored -> new ArrayDeque<>())
                    .addLast(callId);
        }

        /**
         * 解析并提交一次 FunctionResponse 的工具结果判断。
         *
         * @param event    承载响应的 ADK Event
         * @param response ADK 工具执行响应
         */
        private void assessResponse(Event event, FunctionResponse response) {
            String toolName = response.name().orElse("unknown_tool");
            String responseId = resolveResponseId(response, toolName);
            String responseKey = responseId == null || responseId.isBlank()
                    ? event.id() + "|" + toolName
                    : responseId + "|" + toolName;

            // 流式事件可能重复携带同一个完整响应，只允许调用一次 Jev/Laya。
            if (!processedResponses.add(responseKey)) {
                return;
            }

            PendingToolCall pendingCall = responseId == null
                    ? null
                    : pendingById.remove(responseId);
            Map<String, Object> result = new LinkedHashMap<>(response.response().orElse(Map.of()));

            Object outputValue = result.containsKey("error")
                    ? result.get("error")
                    : result.containsKey("output")
                    ? result.get("output")
                    : result.getOrDefault("result", result);

            String output = stringify(outputValue);
            String command = resolveCommand(result, pendingCall);
            boolean reportedSuccess = !Boolean.FALSE.equals(result.get("success"))
                    && result.get("error") == null;

            /*
             * 子 Agent 不再执行用户意图分类。没有主 Agent 意图快照时无法构建
             * ToolOutcomeDecisionRequest，只记录并跳过，不影响子 Agent 工具执行。
             */
            if (rootIntent == null) {
                log.debug(
                        "跳过子Agent工具结果判断，缺少主意图快照 toolName={} sourceAgent={} toolCallId={}",
                        toolName,
                        sourceAgent,
                        responseId);
                return;
            }

            try {
                ToolOutcomeDecisionRequest request = new ToolOutcomeDecisionRequest(
                        delegatedRequest,
                        rootIntent,
                        rootIntentConfidence,
                        sourceAgent + "/" + toolName,
                        command,
                        reportedSuccess,
                        output);

                Optional<StructuredDecision<ToolOutcome>> decision =
                        toolOutcomeDecisionPort.assess(request);

                /*
                 * 适配器已经记录所有成功解析结果，包括影子模式和低概率结果。
                 * 这里仅在正式返回结果时补充子 Agent 与调用 ID，方便定位具体内部工具。
                 */
                decision.ifPresent(value -> log.debug(
                        "子Agent工具结果判断完成 toolName={} sourceAgent={} outcome={} "
                                + "reportedSuccess={} answerProbability={} toolCallId={}",
                        toolName,
                        sourceAgent,
                        value.choice(),
                        reportedSuccess,
                        value.answerProbability(),
                        responseId));
            } catch (RuntimeException exception) {
                /*
                 * System One 是辅助判断能力。即使端口实现、参数或第三方响应出现异常，
                 * 也必须保持 fail-open，不能破坏子 Agent 的正常工具链。
                 */
                log.warn(
                        "子Agent工具结果判断异常，已忽略 toolName={} sourceAgent={} "
                                + "exception={} message={} toolCallId={}",
                        toolName,
                        sourceAgent,
                        exception.getClass().getSimpleName(),
                        exception.getMessage(),
                        responseId);

                log.debug("子Agent工具结果判断异常详情", exception);
            }
        }

        /**
         * 解析响应对应的工具调用 ID，并维护缺失 ID 场景的顺序队列。
         *
         * @param response ADK 工具响应
         * @param toolName 工具名称
         * @return 匹配到的调用 ID；完全无法配对时返回 null
         */
        private String resolveResponseId(FunctionResponse response, String toolName) {
            Optional<String> explicitId = response.id().filter(id -> !id.isBlank());
            Deque<String> pendingIds = pendingIdsByToolName.get(toolName);

            if (explicitId.isPresent()) {
                String callId = explicitId.get();

                // 显式 ID 已完成配对时，也要从顺序队列移除，避免污染后续无 ID 响应。
                if (pendingIds != null) {
                    pendingIds.remove(callId);
                }

                return callId;
            }

            return pendingIds == null ? null : pendingIds.pollFirst();
        }

        /**
         * 获取工具命令或关键输入。
         *
         * <p>优先使用响应中的 command；缺失时读取 FunctionCall 参数中的 command；
         * 非命令类工具则序列化完整参数，让 System One 仍能理解工具实际输入。</p>
         *
         * @param result      FunctionResponse 结果 Map
         * @param pendingCall 与响应配对的 FunctionCall；可能为空
         * @return 工具命令或结构化参数文本
         */
        private String resolveCommand(Map<String, Object> result, PendingToolCall pendingCall) {
            String responseCommand = stringify(result.get("command"));

            if (!responseCommand.isBlank()) {
                return responseCommand;
            }

            if (pendingCall == null || pendingCall.arguments().isEmpty()) {
                return "";
            }

            String argumentCommand = stringify(pendingCall.arguments().get("command"));

            return argumentCommand.isBlank()
                    ? stringify(pendingCall.arguments())
                    : argumentCommand;
        }

        /**
         * 将工具值转换成稳定文本。
         *
         * @param value 字符串、Map、List 或其他工具返回对象
         * @return 字符串原文或 JSON；无法序列化时回退 String.valueOf
         */
        private String stringify(Object value) {
            if (value == null) {
                return "";
            }

            if (value instanceof String text) {
                return text;
            }

            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
    }

    /**
     * 尚未配对响应的工具调用快照。
     *
     * @param arguments 工具调用参数的防御性副本
     */
    private record PendingToolCall(Map<String, Object> arguments) {
    }

    /**
     * 把可空文本归一化为空字符串。
     */
    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    /**
     * 将意图置信度约束到 ToolOutcomeDecisionRequest 接受的 0 到 1 区间。
     */
    private static double normalizeProbability(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }

        return Math.max(0.0, Math.min(1.0, value));
    }
}
