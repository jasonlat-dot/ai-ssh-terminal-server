package com.jasonlat.ai.domain.agent.adapter.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 每轮模型请求的快速工具筛选端口。
 *
 * <p>领域层只描述“根据当前任务从候选工具中保留相关工具”的能力，不关心底层
 * 使用 Jev、Laya 或其他兼容 System One 的服务。实现返回 {@link Optional#empty()}
 * 时，调用方必须保留全部原始工具，确保外部决策服务异常不会削弱 Agent 能力。</p>
 */
public interface IToolSelectionDecisionPort {

    /**
     * 判断当前模型请求应该看到哪些工具。
     *
     * @param request 当前 Agent、任务上下文和完整候选工具
     * @return 可以正式使用的筛选结果；空表示必须回退完整工具集合
     */
    Optional<ToolSelectionDecision> select(ToolSelectionDecisionRequest request);

    /**
     * System One 对单个工具的二元判断。
     */
    enum ToolUseDecision {

        /** 当前任务或后续步骤可能需要该工具。 */
        USE,

        /** 该工具与当前任务明显无关，可以从本轮模型请求中隐藏。 */
        SKIP
    }

    /**
     * 提供给快速决策服务的工具摘要。
     *
     * @param name        工具在 ADK 请求中的唯一名称
     * @param description 工具用途说明；允许为空，但不能为 null
     */
    record ToolCandidate(String name, String description) {

        /**
         * 校验工具候选并归一化可空说明。
         */
        public ToolCandidate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("工具筛选候选名称不能为空");
            }

            description = description == null ? "" : description;
        }
    }

    /**
     * 一次工具筛选请求。
     *
     * @param userMessage   当前主 Agent 用户任务，或子 Agent 收到的委派任务
     * @param recentContext 最近模型调用和工具完成情况的安全摘要，不包含工具原始输出
     * @param agentName     当前实际执行模型请求的 Agent 名称
     * @param currentIntent 主 Agent 已识别的意图；没有快照时使用空字符串
     * @param candidates    当前 LlmRequest 中原本可用的完整工具集合
     */
    record ToolSelectionDecisionRequest(
            String userMessage,
            String recentContext,
            String agentName,
            String currentIntent,
            List<ToolCandidate> candidates
    ) {

        /**
         * 在请求离开领域层前完成校验和防御性复制。
         */
        public ToolSelectionDecisionRequest {
            userMessage = userMessage == null ? "" : userMessage.trim();
            recentContext = recentContext == null ? "" : recentContext.trim();
            agentName = agentName == null || agentName.isBlank() ? "unknown" : agentName;
            currentIntent = currentIntent == null ? "" : currentIntent;

            if (userMessage.isBlank() && recentContext.isBlank()) {
                throw new IllegalArgumentException("工具筛选至少需要用户任务或最近上下文");
            }

            if (candidates == null || candidates.size() < 2) {
                throw new IllegalArgumentException("工具筛选至少需要两个候选工具");
            }

            /*
             * 除了防止调用期间被修改，还要提前拒绝重名工具。否则仅按名称返回选择结果时
             * 无法确定应该保留哪个 BaseTool 实例。
             */
            Set<String> names = new LinkedHashSet<>();

            for (ToolCandidate candidate : candidates) {
                if (candidate == null) {
                    throw new IllegalArgumentException("工具筛选候选不能为空");
                }

                if (!names.add(candidate.name())) {
                    throw new IllegalArgumentException("工具筛选候选名称重复: " + candidate.name());
                }
            }

            candidates = List.copyOf(candidates);
        }
    }

    /**
     * 单个工具的结构化判断记录。
     *
     * @param toolName          工具名称
     * @param decision         USE 或 SKIP
     * @param answerProbability {@code probabilities[choice]} 对应概率
     * @param nativeConfidence 供应商原生 confidence，仅用于日志和校准
     */
    record ToolDecision(
            String toolName,
            ToolUseDecision decision,
            double answerProbability,
            double nativeConfidence
    ) {

        /**
         * 保证正式进入领域结果的工具判断完整且概率合法。
         */
        public ToolDecision {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("工具判断名称不能为空");
            }

            if (decision == null) {
                throw new IllegalArgumentException("工具判断结论不能为空");
            }

            if (!Double.isFinite(answerProbability)
                    || answerProbability < 0.0
                    || answerProbability > 1.0) {
                throw new IllegalArgumentException("工具判断答案概率必须位于 0 到 1 之间");
            }
        }
    }

    /**
     * 可以应用到本轮 LlmRequest 的工具筛选结果。
     *
     * @param selectedToolNames 最终应保留的工具名；包括低概率 SKIP 的安全保留工具
     * @param decisions         System One 对实际参与判断工具给出的逐项结果
     * @param provider          决策协议或供应商标识
     * @param model             服务端实际返回的模型名称
     */
    record ToolSelectionDecision(
            Set<String> selectedToolNames,
            Map<String, ToolDecision> decisions,
            String provider,
            String model
    ) {

        /**
         * 对集合执行防御性复制，避免一次筛选完成后被并发修改。
         */
        public ToolSelectionDecision {
            if (selectedToolNames == null || selectedToolNames.isEmpty()) {
                throw new IllegalArgumentException("工具筛选结果至少必须保留一个工具");
            }

            selectedToolNames = Collections.unmodifiableSet(
                    new LinkedHashSet<>(selectedToolNames));
            decisions = decisions == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(decisions));
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
        }
    }
}
