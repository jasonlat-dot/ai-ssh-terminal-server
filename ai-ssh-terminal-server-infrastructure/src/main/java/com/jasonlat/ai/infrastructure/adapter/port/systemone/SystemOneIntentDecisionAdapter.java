package com.jasonlat.ai.infrastructure.adapter.port.systemone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jasonlat.ai.domain.agent.adapter.port.IIntentDecisionPort;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Jev/Laya System One Choice 协议的意图决策适配器。
 *
 * <p>HTTP、鉴权、超时、异常降级和公共 Choice 解析由
 * {@link SystemOneAdapterSupport} 负责。本类只维护意图分类自己的 state、
 * 候选意图映射、概率门槛、影子模式和业务日志。</p>
 */
@Slf4j
@Component
public class SystemOneIntentDecisionAdapter
        extends SystemOneAdapterSupport
        implements IIntentDecisionPort {

    /** System One 会使用该 ID 把答案写入 {@code answers.intent}。 */
    private static final String QUESTION_ID = "intent";

    /**
     * 意图 Choice 的判断说明。
     */
    private static final String QUESTION_INSTRUCTIONS = """
            判断 state.current_user_message 的主要 SSH 运维意图。
            state.conversation_context 只能作为辅助上下文。
            如果消息同时包含两个及以上明确任务，选择 COMPOUND。
            如果消息缺少足够信息或不属于任何候选类型，选择 UNKNOWN。
            只选择一个最符合的候选项。
            """;

    /**
     * 创建 System One 意图决策适配器。
     *
     * @param settings     System One 统一连接和意图能力参数
     * @param objectMapper Spring Boot 统一管理的 ObjectMapper
     */
    public SystemOneIntentDecisionAdapter(
            SystemOneDecisionSettings settings,
            ObjectMapper objectMapper
    ) {
        super(settings, objectMapper);
    }

    /**
     * 调用 System One 完成结构化意图分类。
     *
     * @param request 已校验的领域层意图分类请求
     * @return 结果达到门槛且非影子模式时返回意图，否则返回 Optional.empty()
     */
    @Override
    public Optional<IntentDecision> classify(IntentDecisionRequest request) {
        // 全局功能关闭时不构建请求，也不产生任何网络调用。
        if (!settings.enabled()) {
            return Optional.empty();
        }

        Optional<ChoiceResponse> response = executeChoice(
                buildRequestBody(request),
                QUESTION_ID,
                "意图分类",
                "回退原有 LLM");

        if (response.isEmpty()) {
            return Optional.empty();
        }

        Optional<IntentDecision> mapped = mapIntentDecision(response.get());

        if (mapped.isEmpty()) {
            log.warn(
                    "System One 返回未知意图 choice={} provider={} elapsedMs={}",
                    response.get().choice(),
                    providerName(),
                    response.get().elapsedMillis());

            return Optional.empty();
        }

        IntentDecision decision = mapped.get();
        boolean accepted = decision.answerProbability() >= settings.minAnswerProbability();

        log.info(
                "System One 意图分类 intent={} accepted={} answerProbability={} provider={} "
                        + "model={} nativeConfidence={} shadowMode={} elapsedMs={}",
                decision.intent(),
                accepted,
                decision.answerProbability(),
                decision.provider(),
                decision.model(),
                decision.nativeConfidence(),
                settings.shadowMode(),
                response.get().elapsedMillis());

        /*
         * 影子模式只观测结果；概率不足时继续执行原有 LLM 分类流程。
         */
        if (settings.shadowMode() || !accepted) {
            return Optional.empty();
        }

        return Optional.of(decision);
    }

    /**
     * 构建意图分类专用 state 和 Choice 问题。
     *
     * @param request 领域层意图分类请求
     * @return 完整 System One 请求节点
     */
    private ObjectNode buildRequestBody(IntentDecisionRequest request) {
        ObjectNode root = createRequestRoot();
        ObjectNode state = root.putObject("state");

        state.put("current_user_message", request.message());
        state.put("conversation_context", request.context());

        addChoiceQuestion(
                root,
                QUESTION_ID,
                QUESTION_INSTRUCTIONS,
                request.criteria());

        return root;
    }

    /**
     * 把协议级字符串 Choice 映射成领域层意图结果。
     *
     * @param response 已完成公共协议校验的 Choice 响应
     * @return choice 和概率都能映射到 IntentTypeEnumVO 时返回领域结果
     */
    private Optional<IntentDecision> mapIntentDecision(ChoiceResponse response) {
        IntentTypeEnumVO intent;

        try {
            intent = IntentTypeEnumVO.valueOf(response.choice().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        Map<IntentTypeEnumVO, Double> probabilities = new LinkedHashMap<>();

        response.probabilities().forEach((candidateName, probability) -> {
            try {
                IntentTypeEnumVO candidate = IntentTypeEnumVO.valueOf(
                        candidateName.toUpperCase(Locale.ROOT));
                probabilities.put(candidate, probability);
            } catch (IllegalArgumentException ignored) {
                // 新增供应商选项不能让旧客户端整次分类失败。
                log.debug("忽略 System One 返回的未知意图选项 option={}", candidateName);
            }
        });

        if (!probabilities.containsKey(intent)) {
            return Optional.empty();
        }

        return Optional.of(new IntentDecision(
                intent,
                response.answerProbability(),
                response.nativeConfidence(),
                probabilities,
                providerName(),
                response.model(),
                response.rawResponse()));
    }
}
