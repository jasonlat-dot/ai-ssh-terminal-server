package com.jasonlat.ai.infrastructure.adapter.port.systemone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jasonlat.ai.domain.agent.adapter.port.IToolSelectionDecisionPort;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import com.jasonlat.ai.infrastructure.model.settings.ToolSelectionDecisionSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Jev/Laya System One 多 Choice 协议的每轮工具筛选适配器。
 *
 * <p>每个候选工具对应一个 USE/SKIP Choice 问题，所有问题通过一次 HTTP 请求发送。
 * HTTP、鉴权、超时和公共响应解析由 {@link SystemOneAdapterSupport} 统一处理。</p>
 *
 * <p>安全策略采用“宁可多保留，不可误删除”：低概率 SKIP、未进入本次问题上限的工具、
 * 永久保留名单以及最低保留数量补位工具都会继续出现在 LlmRequest 中。</p>
 */
@Slf4j
@Component
public class SystemOneToolSelectionDecisionAdapter
        extends SystemOneAdapterSupport
        implements IToolSelectionDecisionPort {

    /** 每个工具问题 ID 的固定前缀；索引与请求候选顺序稳定对应。 */
    private static final String QUESTION_ID_PREFIX = "tool_";

    /**
     * 工具筛选的固定二元判定标准。
     */
    private static final Map<ToolUseDecision, String> TOOL_USE_CRITERIA = createToolUseCriteria();

    /** 工具筛选能力自己的灰度开关、门槛和输入规模限制。 */
    private final ToolSelectionDecisionSettings selectionSettings;

    /**
     * 创建 System One 工具筛选适配器。
     *
     * @param providerSettings  System One 统一连接配置
     * @param selectionSettings 工具筛选独立配置
     * @param objectMapper      Spring Boot 统一管理的 JSON 序列化器
     */
    public SystemOneToolSelectionDecisionAdapter(
            SystemOneDecisionSettings providerSettings,
            ToolSelectionDecisionSettings selectionSettings,
            ObjectMapper objectMapper
    ) {
        super(providerSettings, objectMapper);
        this.selectionSettings = selectionSettings;
    }

    /**
     * 判断本轮模型请求应该保留哪些工具。
     *
     * @param request 当前任务、Agent、意图快照和候选工具
     * @return 非影子模式下的安全工具集合；任何失败均返回空以保留全部工具
     */
    @Override
    public Optional<ToolSelectionDecision> select(ToolSelectionDecisionRequest request) {
        // 全局决策关闭或工具筛选子能力关闭时，不构建也不发送请求。
        if (!settings.enabled() || !selectionSettings.enabled()) {
            return Optional.empty();
        }

        PreparedSelection prepared = prepareCandidates(request.candidates());

        /*
         * 所有工具都位于永久保留名单时没有调用价值。返回 empty 后插件会保留原集合，
         * 结果与正式选择全部工具完全一致。
         */
        if (prepared.questions().isEmpty()) {
            return Optional.empty();
        }

        ObjectNode requestBody = buildRequestBody(request, prepared.questions());
        Optional<Map<String, ChoiceResponse>> optionalResponses = executeChoices(
                requestBody,
                prepared.questions().keySet(),
                "工具筛选",
                "保留全部工具");

        if (optionalResponses.isEmpty()) {
            return Optional.empty();
        }

        return mapDecision(request, prepared, optionalResponses.get());
    }

    /**
     * 区分需要询问 System One 的工具与必须直接保留的工具。
     *
     * @param candidates 原始候选工具
     * @return 问题 ID 到候选工具的映射，以及无需询问就保留的工具名
     */
    private PreparedSelection prepareCandidates(List<ToolCandidate> candidates) {
        Map<String, ToolCandidate> questions = new LinkedHashMap<>();
        Set<String> preselectedToolNames = new LinkedHashSet<>();
        int questionIndex = 0;

        for (ToolCandidate candidate : candidates) {
            /*
             * 永久保留工具不发送给 System One；超过单次判断上限的溢出工具也直接保留，
             * 避免因为服务端问题数量限制而静默丢失能力。
             */
            if (selectionSettings.mustKeep(candidate.name())
                    || questions.size() >= selectionSettings.maxToolsPerRequest()) {
                preselectedToolNames.add(candidate.name());
                continue;
            }

            questions.put(QUESTION_ID_PREFIX + questionIndex, candidate);
            questionIndex++;
        }

        return new PreparedSelection(questions, preselectedToolNames);
    }

    /**
     * 构建包含多个 USE/SKIP 问题的 System One 请求。
     *
     * @param request   领域工具筛选请求
     * @param questions 问题 ID 与候选工具映射
     * @return 完整请求根节点
     */
    private ObjectNode buildRequestBody(ToolSelectionDecisionRequest request, Map<String, ToolCandidate> questions) {
        ObjectNode root = createRequestRoot();
        ObjectNode state = root.putObject("state");

        state.put("user_message", truncate(request.userMessage(), 2_000));
        state.put("recent_context",
                truncate(request.recentContext(), selectionSettings.maxContextCharacters()));
        state.put("agent_name", truncate(request.agentName(), 256));
        state.put("current_intent", truncate(request.currentIntent(), 128));
        state.put("candidate_tool_count", request.candidates().size());

        questions.forEach((questionId, candidate) -> addChoiceQuestion(
                root,
                questionId,
                buildQuestionInstructions(candidate),
                TOOL_USE_CRITERIA));
        /*
         * {
         *   "state": {
         *     "user_message": "用户提问...",
         *     "recent_context": "user:xxx\assistant called tool:xxx...",
         *     "agent_name": "运维助手",
         *     "current_intent": "查询内存",
         *     "candidate_tool_count": 3
         *   },
         *   "question_1": {
         *     "instruction": "判断是否调用getMemInfo工具",
         *     "criteria": "TOOL_USE_CRITERIA常量内容"
         *   },
         *   "question_2": {
         *     "instruction": "判断是否调用getDiskInfo工具",
         *     "criteria": "TOOL_USE_CRITERIA常量内容"
         *   }
         * }
         */
        return root;
    }

    /**
     * 为一个工具生成独立判断说明。
     *
     * @param candidate 当前候选工具
     * @return 明确包含工具名称和有界说明的 instructions
     */
    private String buildQuestionInstructions(ToolCandidate candidate) {
        String description = truncate(
                candidate.description(),
                selectionSettings.maxDescriptionCharacters());

        return """
                判断下面这个工具是否应该出现在当前 Agent 的本轮模型请求中。
                工具名称：%s
                工具说明：%s
                请结合 state.user_message、state.recent_context、state.current_intent 和当前 Agent 判断。
                只在工具与当前任务及其合理后续步骤明显无关时选择 SKIP；不确定时选择 USE。
                """.formatted(candidate.name(), description.isBlank() ? "无" : description);
    }

    /**
     * 把多问题协议响应映射成领域工具集合。
     *
     * @param request   原始领域请求
     * @param prepared  问题与预保留工具
     * @param responses 完整的多 Choice 响应
     * @return 合法时返回正式或影子结果；未知 choice 时整体 fail-open
     */
    private Optional<ToolSelectionDecision> mapDecision(
            ToolSelectionDecisionRequest request,
            PreparedSelection prepared,
            Map<String, ChoiceResponse> responses
    ) {
        Set<String> selectedToolNames = new LinkedHashSet<>(
                prepared.preselectedToolNames());
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        int lowProbabilityKeptCount = 0;

        for (Map.Entry<String, ToolCandidate> entry : prepared.questions().entrySet()) {
            ChoiceResponse response = responses.get(entry.getKey());

            if (response == null) {
                return Optional.empty();
            }

            ToolUseDecision useDecision;

            try {
                useDecision = ToolUseDecision.valueOf(
                        response.choice().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                log.warn(
                        "System One 返回未知工具筛选结论 toolName={} agentName={} choice={}",
                        entry.getValue().name(),
                        request.agentName(),
                        response.choice());
                return Optional.empty();
            }

            ToolDecision toolDecision = new ToolDecision(
                    entry.getValue().name(),
                    useDecision,
                    response.answerProbability(),
                    response.nativeConfidence());
            decisions.put(toolDecision.toolName(), toolDecision);

            boolean accepted = response.answerProbability()
                    >= selectionSettings.minAnswerProbability();

            /*
             * USE 无论概率高低都保留。SKIP 只有达到本地门槛才真正删除；
             * 低概率 SKIP 采用 fail-open，避免模糊判断误伤 Agent 能力。
             */
            if (useDecision == ToolUseDecision.USE || !accepted) {
                selectedToolNames.add(toolDecision.toolName());

                if (!accepted) {
                    lowProbabilityKeptCount++;
                }
            }

            log.debug(
                    "System One 单工具筛选 toolName={} agentName={} decision={} accepted={} "
                            + "answerProbability={} nativeConfidence={}",
                    toolDecision.toolName(),
                    request.agentName(),
                    toolDecision.decision(),
                    accepted,
                    toolDecision.answerProbability(),
                    toolDecision.nativeConfidence());
        }

        ensureMinimumTools(
                request.candidates(),
                selectedToolNames,
                selectionSettings.minRetainedTools());

        Set<String> removedToolNames = new LinkedHashSet<>();
        request.candidates().stream()
                .map(ToolCandidate::name)
                .filter(name -> !selectedToolNames.contains(name))
                .forEach(removedToolNames::add);

        ChoiceResponse firstResponse = responses.values().iterator().next();

        log.info(
                "System One 工具筛选 agentName={} retainedTools={} removedTools={} "
                        + "candidateCount={} retainedCount={} lowProbabilityKeptCount={} "
                        + "shadowMode={} provider={} model={} elapsedMs={}",
                request.agentName(),
                selectedToolNames,
                removedToolNames,
                request.candidates().size(),
                selectedToolNames.size(),
                lowProbabilityKeptCount,
                selectionSettings.shadowMode(),
                providerName(),
                firstResponse.model(),
                firstResponse.elapsedMillis());

        // 影子模式完整调用并记录“将会保留/删除什么”，但不修改实际 LlmRequest。
        if (selectionSettings.shadowMode()) {
            return Optional.empty();
        }

        return Optional.of(new ToolSelectionDecision(
                selectedToolNames,
                decisions,
                providerName(),
                firstResponse.model()));
    }

    /**
     * 当 System One 删除过多工具时，按照原始顺序补足最低数量。
     *
     * @param candidates        原始有序候选集合
     * @param selectedToolNames 当前保留名称集合，将被原地补充
     * @param minimum           最低保留数量
     */
    private void ensureMinimumTools(
            List<ToolCandidate> candidates,
            Set<String> selectedToolNames,
            int minimum
    ) {
        int target = Math.min(minimum, candidates.size());

        if (selectedToolNames.size() >= target) {
            return;
        }

        for (ToolCandidate candidate : candidates) {
            selectedToolNames.add(candidate.name());

            if (selectedToolNames.size() >= target) {
                return;
            }
        }
    }

    /**
     * 创建固定 USE/SKIP 标准。
     */
    private static Map<ToolUseDecision, String> createToolUseCriteria() {
        Map<ToolUseDecision, String> criteria = new LinkedHashMap<>();

        criteria.put(ToolUseDecision.USE,
                "当前任务、当前步骤或合理的后续步骤可能需要该工具；存在不确定性时也选择 USE");
        criteria.put(ToolUseDecision.SKIP,
                "该工具与当前任务和合理后续步骤明显无关，本轮隐藏不会阻碍任务完成");

        return Collections.unmodifiableMap(criteria);
    }

    /**
     * 工具候选预处理结果。
     *
     * @param questions            需要 System One 判断的问题与工具映射
     * @param preselectedToolNames 永久保留或因问题上限溢出而直接保留的工具
     */
    private record PreparedSelection(
            Map<String, ToolCandidate> questions,
            Set<String> preselectedToolNames) {

        /** 冻结内部集合，避免构建请求和映射响应期间被修改。 */
        private PreparedSelection {
            questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
            preselectedToolNames = Collections.unmodifiableSet(
                    new LinkedHashSet<>(preselectedToolNames));
        }
    }
}
