package com.jasonlat.ai.domain.agent.service.intent;


import com.jasonlat.ai.domain.agent.model.valobj.intent.*;
import com.jasonlat.ai.domain.agent.service.IIntentService;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import com.jasonlat.ai.domain.agent.service.intent.classifier.node.RootNode;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别服务（调度入口）
 * <p>
 * 三层级联策略：
 * <ol>
 *   <li>规则分类（< 1ms）：置信度 ≥ 0.8 直接返回</li>
 *   <li>LLM 分类（100~500ms）：置信度 ≥ 0.5 采用，否则回退规则结果</li>
 *   <li>反馈回路：下游执行失败时触发重分类（最多重试一次，避免循环）</li>
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
 *      │  reportFeedback(sessionId, lastIntent, success, toolResult)
 *      ▼
 *   反馈回路：失败 && conf<0.7 && 结果像意图走偏 → 用候选意图重分类（仅一次）
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
     * 反馈回路：下游执行工具后回报结果。
     * <p>
     * 策略：
     * <ul>
     *   <li>成功：记录成功，返回 null（维持原意图）</li>
     *   <li>失败且原意图置信度 < 阈值：用候选意图递补；无候选则返回 UNKNOWN（全交主模型）</li>
     *   <li>失败但原意图置信度高：记录失败但不重分类，避免误判</li>
     * </ul>
     * <p>判定流程：
     * <pre>
     *   reportFeedback(lastIntent, success, toolResult)
     *     ├─ lastIntent==null 或已 reclassified  → 返回 null（不连锁重分类）
     *     ├─ success=true                       → 记录成功，返回 null
     *     ├─ success=false && conf ≥ 0.7        → 仅记录失败，不重分类
     *     ├─ success=false && 结果不像意图走偏   → 返回 null
     *     └─ 满足重分类条件：
     *          ├─ 有候选 → 候选[0] 递补，conf*0.8，reclassified=true
     *          └─ 无候选 → UNKNOWN(conf=0)，全交主模型
     * </pre>
     */
    @Override
    public IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent, boolean success, String toolResult) {
        // 已重分类过的结果不再二次重分类，避免连锁递补
        if (lastIntent == null || lastIntent.isReclassified()) {
            return null;
        }
        contextTracker.recordFeedback(sessionId, success);

        // 仅在确认为失败时记录，避免一轮多工具结果重复累计
        if (success) return null;

        // 高置信度意图不轻易重分类（可能只是工具偶发失败）
        if (lastIntent.getConfidence() >= FEEDBACK_RECLASSIFY_THRESHOLD) {
            return null;
        }

        // 工具结果明确提示"找不到/不存在" → 当前意图大概率走偏
        if (!looksLikeIntentMismatch(toolResult)) {
            return null;
        }

        // 获取候选意图
        List<IntentTypeEnumVO> candidates = lastIntent.getCandidateIntents();
        if (candidates == null || candidates.isEmpty()) {
            // 无候选 → UNKNOWN，全交主模型
            IntentResultVO unknown = IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0)
                    .entities(lastIntent.getEntities())
                    .candidateIntents(List.of())
                    .reclassified(true)
                    .build();
            contextTracker.updateContext(sessionId, unknown);
            return unknown;
        }

        // 用候选意图递补
        IntentResultVO reclassified = IntentResultVO.builder()
                .intent(candidates.get(0))
                .confidence(lastIntent.getConfidence() * 0.8)
                .entities(lastIntent.getEntities())
                // 剔除当前候选意图
                .candidateIntents(candidates.size() > 1
                        ? new ArrayList<>(candidates.subList(1, candidates.size()))
                        : List.of())
                .rawResponse(lastIntent.getRawResponse())
                .reclassified(true)
                .build();
        contextTracker.updateContext(sessionId, reclassified);
        return reclassified;


    }

    /**
     * 工具结果是否暗示当前意图走偏（服务/文件/命令不存在类错误）。
     * 与 AiCallNode 的失败判定保持一致的特征词集合。
     * <p>
     * 特征词同时覆盖中英文，命中即认为当前意图大概率选错（如 CONFIGURE 找不到配置文件）。
     * 该判定在两处复用：反馈回路重分类门控、AiCallNode.handleIntentFeedback 成功判定。
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
