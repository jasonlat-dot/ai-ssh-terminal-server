package com.jasonlat.ai.infrastructure.adapter.port.systemone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import com.jasonlat.ai.infrastructure.model.settings.ToolOutcomeDecisionSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Jev/Laya System One Choice 协议的工具结果判断适配器。
 *
 * <p>HTTP 客户端、鉴权请求头、请求超时、异常降级和公共 Choice 响应解析
 * 统一交给 {@link SystemOneAdapterSupport}。本类只负责工具结果判断专有的
 * state、候选结论映射、概率门槛、影子模式和业务日志。</p>
 *
 * <p>任何协议或网络失败都会由公共支持层执行 fail-open，当前反馈回路随后
 * 继续使用原有本地关键词规则，不会因为 Jev/Laya 暂时不可用而中断 Agent。</p>
 */
@Slf4j
@Component
public class SystemOneToolOutcomeDecisionAdapter
        extends SystemOneAdapterSupport
        implements IToolOutcomeDecisionPort {

    /** System One 会使用该 ID 把答案写入 {@code answers.tool_outcome}。 */
    private static final String QUESTION_ID = "tool_outcome";

    /**
     * 工具结果判断说明。
     *
     * <p>这里明确区分“工具本身执行失败”和“当前意图方向错误”，避免连接失败、
     * 权限不足等普通执行问题被误判成需要重新识别用户意图。</p>
     */
    private static final String QUESTION_INSTRUCTIONS = """
            根据 state 中的用户目标、当前意图、工具信息和工具结果，判断本次工具执行的业务含义。
            reported_success 只表示工具执行层是否成功，不能单独证明当前意图是否正确。
            权限不足、连接失败、超时、文件不存在、命令不存在等通常属于 EXECUTION_FAILED；
            只有工具选择、命令方向或结果内容明显表明 current_intent 偏离 user_message 时，才选择 INTENT_MISMATCH。
            工具执行成功且结果与用户目标一致或提供了有效证据时选择 SUCCESS。
            证据不足、输出为空或无法可靠区分时选择 INCONCLUSIVE。
            只选择一个最符合的候选项。
            """;

    /**
     * 固定的四类工具结果判定标准。
     *
     * <p>LinkedHashMap 保证发送给 System One 的候选顺序稳定，便于对比日志、
     * 排查模型变化以及进行后续概率校准。</p>
     */
    private static final Map<ToolOutcome, String> OUTCOME_CRITERIA = createOutcomeCriteria();

    /**
     * 工具结果判断能力自己的开关、影子模式、概率门槛和文本长度限制。
     */
    private final ToolOutcomeDecisionSettings outcomeSettings;

    /**
     * 创建工具结果结构化判断适配器。
     *
     * @param providerSettings System One 统一地址、密钥、模型和超时配置
     * @param outcomeSettings  工具结果判断能力的独立运行参数
     * @param objectMapper     Spring Boot 统一管理的 JSON 序列化器
     */
    public SystemOneToolOutcomeDecisionAdapter(
            SystemOneDecisionSettings providerSettings,
            ToolOutcomeDecisionSettings outcomeSettings,
            ObjectMapper objectMapper
    ) {
        // 公共父类负责保存统一配置、ObjectMapper，并创建可复用的 HTTP 客户端。
        super(providerSettings, objectMapper);
        this.outcomeSettings = outcomeSettings;
    }

    /**
     * 调用 Jev 或 Laya 判断本次工具结果是否与当前意图存在偏差。
     *
     * @param request 已在领域层完成基本校验的工具结果判断请求
     * @return 达到本地概率门槛且非影子模式时返回结构化结论，否则返回空
     */
    @Override
    public Optional<StructuredDecision<ToolOutcome>> assess(ToolOutcomeDecisionRequest request) {
        /*
         * System One 全局关闭，或工具结果判断子能力关闭时，不创建网络请求。
         * 调用方收到空值后会自动继续执行已有的本地判断规则。
         */
        if (!settings.enabled() || !outcomeSettings.enabled()) {
            return Optional.empty();
        }

        // 公共支持层统一完成序列化、HTTP 调用、状态码校验、解析和异常降级。
        Optional<ChoiceResponse> response = executeChoice(
                buildRequestBody(request),
                QUESTION_ID,
                "工具结果判断",
                "回退本地规则");

        if (response.isEmpty()) {
            return Optional.empty();
        }

        // 协议层只认识字符串，本适配器在这里把它转换为工具结果领域枚举。
        Optional<StructuredDecision<ToolOutcome>> mapped = mapToolOutcomeDecision(response.get());

        if (mapped.isEmpty()) {
            log.warn(
                    "System One 返回未知工具结果 toolName={} intent={} choice={} provider={} elapsedMs={}",
                    request.toolName(),
                    request.currentIntent(),
                    response.get().choice(),
                    providerName(),
                    response.get().elapsedMillis());

            return Optional.empty();
        }

        StructuredDecision<ToolOutcome> decision = mapped.get();

        /*
         * Jev 与 Laya 的 native confidence 计算方式可能不同。
         * 跨供应商统一以 probabilities[choice] 作为正式采用结果的门槛。
         */
        boolean accepted = decision.answerProbability() >= outcomeSettings.minAnswerProbability();

        log.info(
                "System One 工具结果判断 toolName={} intent={} reportedSuccess={} outcome={} "
                        + "accepted={} answerProbability={} provider={} model={} "
                        + "nativeConfidence={} shadowMode={} elapsedMs={}",
                request.toolName(),
                request.currentIntent(),
                request.reportedSuccess(),
                decision.choice(),
                accepted,
                decision.answerProbability(),
                decision.provider(),
                decision.model(),
                decision.nativeConfidence(),
                outcomeSettings.shadowMode(),
                response.get().elapsedMillis());

        /*
         * 影子模式仍会调用服务、解析响应并记录日志，但不会改变现有反馈流程。
         * 概率未达到门槛时也同样回退本地规则。
         */
        if (outcomeSettings.shadowMode() || !accepted) {
            return Optional.empty();
        }

        return Optional.of(decision);
    }

    /**
     * 构建工具结果判断专用的 state 和 Choice 问题。
     *
     * <p>不同来源的数据分别写入独立字段，而不是拼成自然语言长文本，
     * 这样可以减少 System One 混淆用户目标、命令和执行结果的概率。</p>
     *
     * @param request 领域层工具结果判断请求
     * @return 可由公共支持层直接发送的完整 System One 请求节点
     */
    private ObjectNode buildRequestBody(ToolOutcomeDecisionRequest request) {
        // 公共方法负责创建根节点，并在配置非空时写入 model。
        ObjectNode root = createRequestRoot();
        ObjectNode state = root.putObject("state");

        /*
         * 控制可能包含大量内容的外发字段长度，避免一次工具输出占满请求上下文。
         * 工具结果保留首尾：开头一般是执行上下文，结尾一般是最终状态或错误原因。
         */
        state.put("user_message", truncate(request.userMessage(), 2_000));
        state.put("current_intent", request.currentIntent().name());
        state.put("intent_confidence", request.intentConfidence());
        state.put("tool_name", truncate(request.toolName(), 256));
        state.put("command", truncate(request.command(), 2_000));
        state.put("reported_success", request.reportedSuccess());
        state.put("tool_result", truncateHeadAndTail(request.toolResult(), outcomeSettings.maxResultCharacters()));

        // 公共方法按统一 System One Choice 结构写入 type、instructions 和 criteria。
        addChoiceQuestion(
                root,
                QUESTION_ID,
                QUESTION_INSTRUCTIONS,
                OUTCOME_CRITERIA);

        return root;
    }

    /**
     * 把协议级字符串 Choice 转换为领域层工具结果结论。
     *
     * @param response 已完成公共 Choice 结构校验的协议响应
     * @return choice 和概率能够映射到 ToolOutcome 时返回领域结果，否则返回空
     */
    private Optional<StructuredDecision<ToolOutcome>> mapToolOutcomeDecision(ChoiceResponse response) {
        ToolOutcome outcome;

        try {
            // 对大小写做归一化，以兼容供应商返回小写或混合大小写候选名。
            outcome = ToolOutcome.valueOf(response.choice().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        Map<ToolOutcome, Double> probabilities = new LinkedHashMap<>();

        response.probabilities().forEach((candidateName, probability) -> {
            try {
                ToolOutcome candidate = ToolOutcome.valueOf(
                        candidateName.toUpperCase(Locale.ROOT));
                probabilities.put(candidate, probability);
            } catch (IllegalArgumentException ignored) {
                /*
                 * 供应商将来新增候选项时，旧客户端只忽略未知项，
                 * 不应因此让整个工具反馈流程失败。
                 */
                log.debug("忽略 System One 返回的未知工具结果选项 option={}", candidateName);
            }
        });

        /*
         * 最终 choice 必须同时存在于合法概率分布中；否则响应不完整，
         * 不能把它用于正式的 Agent 反馈判断。
         */
        if (!probabilities.containsKey(outcome)) {
            return Optional.empty();
        }

        return Optional.of(new StructuredDecision<>(
                outcome,
                response.answerProbability(),
                response.nativeConfidence(),
                probabilities,
                providerName(),
                response.model(),
                response.rawResponse()));
    }

    /**
     * 创建固定的工具结果候选项及判定边界。
     *
     * @return 不可变且保持插入顺序的四类判定标准
     */
    private static Map<ToolOutcome, String> createOutcomeCriteria() {
        Map<ToolOutcome, String> criteria = new LinkedHashMap<>();

        criteria.put(
                ToolOutcome.SUCCESS,
                "工具执行成功，结果与用户目标一致，或为完成当前意图提供了有效证据");

        criteria.put(
                ToolOutcome.EXECUTION_FAILED,
                "工具、命令、权限、网络或目标资源执行失败，但当前意图方向仍符合用户目标");

        criteria.put(
                ToolOutcome.INTENT_MISMATCH,
                "当前意图、工具选择或行动方向与用户真实目标明显不一致，需要重新判断意图");

        criteria.put(
                ToolOutcome.INCONCLUSIVE,
                "输出为空、信息不足或证据互相冲突，无法可靠判断成功、失败或意图偏差");

        // 防止运行期间误改静态候选集合，保证发给供应商的协议稳定。
        return Collections.unmodifiableMap(criteria);
    }
}
