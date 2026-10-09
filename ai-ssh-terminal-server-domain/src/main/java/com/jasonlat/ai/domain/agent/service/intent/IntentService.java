package com.jasonlat.ai.domain.agent.service.intent;


import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort.ToolOutcome;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort.ToolOutcomeDecision;
import com.jasonlat.ai.domain.agent.adapter.port.IToolOutcomeDecisionPort.ToolOutcomeDecisionRequest;
import com.jasonlat.ai.domain.agent.model.valobj.intent.*;
import com.jasonlat.ai.domain.agent.service.IIntentService;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import com.jasonlat.ai.domain.agent.service.intent.classifier.node.RootNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 意图识别服务（调度入口）
 * <p>
 * 三层级联策略：
 * <ol>
 *   <li>规则分类（< 1ms）：置信度 ≥ 0.8 直接返回</li>
 *   <li>LLM 分类（100~500ms）：置信度 ≥ 0.5 采用，否则回退规则结果</li>
 *   <li>反馈回路：结构化区分工具执行失败与意图偏差，确认偏差后最多重分类一次</li>
 * </ol>
 * 带 LRU 缓存（200 条目，5 分钟过期），避免重复分类。
 * <p>
 * 可靠性增强：
 * <ul>
 *   <li>低置信度不再硬选，而是带上候选意图交给下游主模型参考</li>
 *   <li>UNKNOWN 语义为"放弃分类、全交主模型"，不再带低置信度硬走 ReAct</li>
 *   <li>连续失败 ≥ 阈值后，跳过规则层直接让主模型兜底</li>
 * </ul>
 *
 * <p>整体流转示意图：
 * <pre>
 *   AiCallNode.doApply
 *      │  configure(agentApi, model)   ← 复用 Agent 配置，不单独建模型
 *      │  classify(sessionId, userId, msg)
 *      ▼
 *   IntentService.classify
 *      ├─ 命中缓存(5min)？ → 直接返回
 *      ├─ 连续失败≥2？      → 跳规则层，LLM 兜底（门槛放宽到 0.3）
 *      ├─ 第1层 规则        → conf≥0.8 采信
 *      ├─ 第2层 LLM         → conf≥0.5 采信，否则回退规则结果
 *      └─ recordAndCache    → 写 ContextTracker + LRU 缓存
 *      ▼
 *   AiCallNode: 注入意图标签到 Prompt；执行工具后调用
 *      │  reportFeedback(sessionId, lastIntent, userMessage, toolName, command, success, toolResult)
 *      ▼
 *   反馈回路：Jev/Laya 判断工具结果语义 → 只有 INTENT_MISMATCH 才重分类（仅一次）
 * </pre>
 *
 * <p>案例（端到端）：
 * <pre>
 *   用户 "查下 redis 是不是挂了"
 *     classify → 规则命中"挂了" conf=0.6 → 下沉 LLM → DIAGNOSE conf=0.8
 *     注入 Prompt 前缀 [用户意图] 诊断问题
 *     工具执行 ssh "redis-cli ping" → 返回 PONG（成功）
 *     reportFeedback(success=true) → 维持 DIAGNOSE
 *
 *   用户 "看下 nginx 配置"（误判为 CONFIGURE conf=0.55）
 *     工具执行 cat nginx.conf → "No such file"（失败）
 *     reportFeedback → 候选[MONITOR] 递补 → 重分类 MONITOR conf=0.44，标记 reclassified
 * </pre>
 */
@Service
@Slf4j
public class IntentService implements IIntentService {

    @Resource(name = "intentClassifierRootNode")
    private RootNode rootNode;

    /**
     * 反馈回路触发重分类的置信度门槛：原意图低于此值才考虑重分类
     */
    private static final double FEEDBACK_RECLASSIFY_THRESHOLD = 0.7;

    @Resource
    private ContextTracker contextTracker;

    /**
     * Jev/Laya 工具结果结构化判断端口。
     *
     * <p>领域服务只依赖端口，不依赖任何 HTTP、JSON 或供应商实现细节。</p>
     */
    @Resource
    private IToolOutcomeDecisionPort toolOutcomeDecisionPort;

    /**
     * 意图分类主入口：三层级联策略 + LRU 缓存。
     * <p>
     * 分类策略按优先级执行：
     * <ol>
     *   <li>缓存命中（5 分钟有效）→ 直接返回缓存结果</li>
     *   <li>连续失败 ≥2 → 跳过规则层，LLM 兜底（门槛放宽至 0.3）</li>
     *   <li>规则分类 → 置信度 ≥ 0.8 直接采用</li>
     *   <li>LLM 分类 → 置信度 ≥ 0.5 采用，否则回退规则结果</li>
     * </ol>
     * <p>
     * 分类结果同时写入 {@link ContextTracker}（意图历史、失败计数）并缓存，
     * 避免短时间内相同消息重复调用分类器。
     * @return 意图识别结果，包含 intent、confidence、entities、candidates 等字段
     */
    @Override
    public IntentResultVO classify(IntentRequestVO intentRequestVO) throws Exception {

        return rootNode.apply(intentRequestVO, new DefaultClassifyFactory.DynamicContext());
    }

    /**
     * 兼容旧调用方的简化反馈入口。
     * <p>
     * 旧签名没有用户消息、工具名称和命令，因此会使用空字符串补齐这些字段，
     * 然后委托给完整反馈入口。新业务代码应优先调用七参数重载方法。
     *
     * @param sessionId 当前会话 ID
     * @param lastIntent 工具执行前的意图结果
     * @param success 工具协议报告的执行状态
     * @param toolResult 工具返回文本
     * @return 重分类后的意图结果；无需修改时返回 null
     */
    @Override
    public IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent, boolean success, String toolResult) {
        /*
         * 保留旧入口，避免其他调用方被迫一次性修改。
         * 缺少用户消息、工具名和命令时，外部判断仍能参考当前意图、success 和结果文本。
         */
        return reportFeedback(
                sessionId,
                lastIntent,
                "",
                "",
                "",
                success,
                toolResult);
    }

    /**
     * 使用完整工具上下文执行反馈判断，并在确认意图偏差时触发一次重分类。
     *
     * <p>处理过程分为三个阶段：</p>
     * <ol>
     *     <li>记录工具协议报告的真实执行状态；</li>
     *     <li>优先请求 Jev/Laya 区分成功、执行失败、意图偏差和证据不足；</li>
     *     <li>快速决策不可用时，回退到原有关键词规则。</li>
     * </ol>
     *
     * <p>只有 {@link ToolOutcome#INTENT_MISMATCH} 才允许改变当前意图。
     * 普通执行失败不会触发重分类，避免把权限、网络、命令错误误认为意图错误。</p>
     *
     * @param sessionId       当前会话 ID
     * @param lastIntent      工具执行前的意图识别结果
     * @param userMessage     用户本轮原始消息
     * @param toolName        实际执行的工具名称
     * @param command         工具执行的命令或关键输入
     * @param reportedSuccess 工具协议返回的原始 success 字段
     * @param toolResult      工具返回的文本结果
     * @return 重分类后的意图结果；无需修改当前意图时返回 null
     */
    @Override
    public IntentResultVO reportFeedback(
            String sessionId,
            IntentResultVO lastIntent,
            String userMessage,
            String toolName,
            String command,
            boolean reportedSuccess,
            String toolResult
    ) {
        // 没有当前意图时缺少比较基准，无法执行工具结果与意图偏差判断。
        if (lastIntent == null) {
            return null;
        }

        /*
         * 失败计数必须记录工具协议的真实执行状态，而不是记录模型的语义结论。
         * 例如命令成功但目标方向错误时，执行层仍然属于成功。
         */
        contextTracker.recordFeedback(sessionId, reportedSuccess);

        FeedbackAssessment assessment = assessToolOutcome(
                lastIntent,
                userMessage,
                toolName,
                command,
                reportedSuccess,
                toolResult);

        /*
         * 已重分类的结果仍然需要让每个主 Agent 工具经过 Jev/Laya 判断，
         * 但不能再次改变意图，避免候选意图连续递补或形成重分类循环。
         */
        if (lastIntent.isReclassified()) {
            return null;
        }

        /*
         * SUCCESS、EXECUTION_FAILED 和 INCONCLUSIVE 都不能证明意图分类错误。
         * 只有明确的 INTENT_MISMATCH 才进入后续重分类流程。
         */
        if (assessment.outcome() != ToolOutcome.INTENT_MISMATCH) {
            return null;
        }

        /*
         * 本地关键词只是低精度兜底，因此继续保留原有“高置信度不自动改写”保护。
         * Jev/Laya 已结合完整上下文做出且通过概率门槛的结构化结论，不受该旧门槛限制。
         */
        if (!assessment.externalDecision()
                && lastIntent.getConfidence() >= FEEDBACK_RECLASSIFY_THRESHOLD) {
            return null;
        }

        /*
         * 当前意图本身置信度很高，而外部判断又明确认为存在偏差时，
         * 两个强信号发生冲突。此时不盲目采用旧分类的第二候选，
         * 而是切换为 UNKNOWN，让主模型结合完整上下文重新规划。
         */
        if (assessment.externalDecision()
                && lastIntent.getConfidence() >= FEEDBACK_RECLASSIFY_THRESHOLD) {
            return reclassifyAsUnknown(sessionId, lastIntent, assessment);
        }

        // 原意图置信度较低时，优先使用现有分类结果中的第一候选意图递补。
        List<IntentTypeEnumVO> candidates = lastIntent.getCandidateIntents();
        if (candidates == null || candidates.isEmpty()) {
            return reclassifyAsUnknown(sessionId, lastIntent, assessment);
        }

        /*
         * 使用候选列表第一项递补，并降低置信度，明确表达这是反馈后的次级判断。
         * 剩余候选继续保留，供后续主模型和诊断日志参考。
         */
        IntentResultVO reclassified = IntentResultVO.builder()
                .intent(candidates.getFirst())
                .confidence(lastIntent.getConfidence() * 0.8)
                .entities(lastIntent.getEntities())
                .candidateIntents(candidates.size() > 1
                        ? new ArrayList<>(candidates.subList(1, candidates.size()))
                        : List.of())
                .rawResponse(lastIntent.getRawResponse())
                .reclassified(true)
                .build();

        contextTracker.updateContext(sessionId, reclassified);

        log.info(
                "工具反馈触发候选意图递补 sessionId={} originalIntent={} reclassifiedIntent={} "
                        + "source={} outcomeProbability={}",
                sessionId,
                lastIntent.getIntent(),
                reclassified.getIntent(),
                assessment.source(),
                assessment.answerProbability());

        return reclassified;
    }

    /**
     * 获取工具结果的语义结论。
     *
     * <p>优先使用 Jev/Laya 的结构化结果。端口返回空时，说明功能关闭、处于影子模式、
     * 调用失败、响应非法或概率不足，此时严格回退到兼容旧行为的本地规则。</p>
     *
     * @param lastIntent      当前意图识别结果
     * @param userMessage     用户本轮原始消息
     * @param toolName        实际工具名称
     * @param command         工具命令或关键输入
     * @param reportedSuccess 工具协议报告的真实执行状态
     * @param toolResult      工具结果文本
     * @return 包含结论、来源和答案概率的内部判断结果
     */
    private FeedbackAssessment assessToolOutcome(
            IntentResultVO lastIntent,
            String userMessage,
            String toolName,
            String command,
            boolean reportedSuccess,
            String toolResult
    ) {
        Optional<ToolOutcomeDecision> externalDecision;

        try {
            ToolOutcomeDecisionRequest request = new ToolOutcomeDecisionRequest(
                    userMessage,
                    lastIntent.getIntent(),
                    lastIntent.getConfidence(),
                    toolName,
                    command,
                    reportedSuccess,
                    toolResult);

            externalDecision = toolOutcomeDecisionPort.assess(request);
        } catch (RuntimeException exception) {
            /*
             * 端口契约本身采用 fail-open。即使未来替换了适配器实现，
             * 也不能让辅助判断异常中断正在进行的 ReAct 工具链。
             */
            log.warn(
                    "工具结果结构化判断异常，回退本地规则 toolName={} intent={} exception={} message={}",
                    toolName,
                    lastIntent.getIntent(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());

            log.debug("工具结果结构化判断异常详情", exception);

            externalDecision = Optional.empty();
        }

        if (externalDecision.isPresent()) {
            ToolOutcomeDecision decision = externalDecision.get();

            return new FeedbackAssessment(
                    decision.outcome(),
                    true,
                    decision.answerProbability(),
                    decision.provider());
        }

        /*
         * 稳定工具协议中的 reportedSuccess 优先于文本猜测。
         * 工具明确报告成功时，本地兜底直接认定 SUCCESS。
         */
        if (reportedSuccess) {
            return new FeedbackAssessment(ToolOutcome.SUCCESS, false, 1.0, "local");
        }

        /*
         * 旧版规则无法可靠区分执行失败与意图偏差。
         * 为保持升级前行为，仅在命中特征词时继续返回 INTENT_MISMATCH；
         * 其他失败统一视为 EXECUTION_FAILED，不触发意图重分类。
         */
        ToolOutcome fallbackOutcome = looksLikeIntentMismatch(toolResult)
                ? ToolOutcome.INTENT_MISMATCH
                : ToolOutcome.EXECUTION_FAILED;

        return new FeedbackAssessment(fallbackOutcome, false, 0.0, "local");
    }

    /**
     * 将当前意图重分类为 UNKNOWN，并交还主模型重新规划。
     *
     * <p>适用于没有候选意图，或高置信度原意图与高概率偏差结论发生冲突的场景。</p>
     *
     * @param sessionId  当前会话 ID
     * @param lastIntent 原意图结果，用于保留已提取实体
     * @param assessment 触发本次重分类的工具结果判断
     * @return 已标记 reclassified 的 UNKNOWN 结果
     */
    private IntentResultVO reclassifyAsUnknown(
            String sessionId,
            IntentResultVO lastIntent,
            FeedbackAssessment assessment
    ) {
        IntentResultVO unknown = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .confidence(0.0)
                .entities(lastIntent.getEntities())
                .candidateIntents(List.of())
                .rawResponse(lastIntent.getRawResponse())
                .reclassified(true)
                .build();

        contextTracker.updateContext(sessionId, unknown);

        log.info(
                "工具反馈将意图重分类为 UNKNOWN sessionId={} originalIntent={} source={} outcomeProbability={}",
                sessionId,
                lastIntent.getIntent(),
                assessment.source(),
                assessment.answerProbability());

        return unknown;
    }

    /**
     * 领域服务内部使用的工具结果判断摘要。
     *
     * @param outcome           成功、执行失败、意图偏差或证据不足
     * @param externalDecision  是否来自已通过概率门槛的 Jev/Laya 正式结果
     * @param answerProbability 外部选中答案概率；本地结果使用 0 或 1
     * @param source            结果来源，例如 jev、laya 或 local
     */
    private record FeedbackAssessment(
            ToolOutcome outcome,
            boolean externalDecision,
            double answerProbability,
            String source
    ) {
    }

    /**
     * 本地兜底规则：工具结果是否包含历史版本使用的意图偏差特征词。
     * <p>
     * 该方法仅在 Jev/Laya 未提供可用结果时使用。关键词无法真正区分
     * “意图错误”和“意图正确但工具执行失败”，所以它只能作为兼容旧行为的低精度兜底，
     * 且仍受低置信度重分类门槛保护。
     * <p>
     * 案例：
     * <pre>
     *   toolResult = "No such file or directory: /etc/nginx/nginx.conf"
     *   -> contains("No such") = true
     *   -> 返回 true（意图走偏，触发重分类）
     *
     *   toolResult = "PONG"
     *   -> 不含任何特征词
     *   -> 返回 false（意图正确，维持原分类）
     *
     *   toolResult = "permission denied"
     *   -> contains("Permission denied") = true
     *   -> 返回 true（权限问题，可能是意图选错目标文件）
     *
     *   toolResult = "100 rows affected"
     *   -> 不含任何特征词
     *   -> 返回 false（执行成功，不重分类）
     * </pre>
     */
    public static boolean looksLikeIntentMismatch(String toolResult) {
        return toolResult != null && (
                toolResult.contains("not found")
                        || toolResult.contains("未找到")
                        || toolResult.contains("不存在")
                        || toolResult.contains("No such")
                        || toolResult.contains("command not found")
                        || toolResult.contains("Permission denied")
                        || toolResult.contains("Connection refused"));
    }

    /**
     * 获取会话当前任务状态。
     * <p>
     * 委托给 {@link ContextTracker}，返回进行中任务的信息（如当前步骤、已完成状态等）。
     *
     * @param sessionId 会话 ID
     * @return 任务状态，若无任务态则返回 null
     */
    @Override
    public TaskStateVO getTaskState(String sessionId) {
        return contextTracker.getTaskState(sessionId);
    }

    @Override
    public IntentResultVO getLastIntentResult(String sessionId) {
        return contextTracker.getLastIntentResult(sessionId);
    }


    /**
     * 更新会话任务状态。
     * <p>
     * 通常由主流程在启动多步任务时调用，用于记录当前意图、步骤索引等信息，
     * 供后续 classify 和 reportFeedback 使用。
     *
     * @param sessionId   会话 ID
     * @param taskState   任务状态对象，可为 null（清除任务态）
     */
    @Override
    public void updateTaskState(String sessionId, TaskStateVO taskState) {
        contextTracker.setTaskState(sessionId, taskState);
    }

}
