package com.jasonlat.ai.domain.agent.adapter.port;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;

import java.util.Optional;

/**
 * 工具执行结果与当前意图偏差判断端口。
 *
 * <p>该端口只描述领域层真正需要的能力：给定用户目标、当前已识别意图、
 * 实际调用的工具以及工具返回结果，判断本次结果属于正常成功、普通执行失败、
 * 意图偏差，还是证据不足。</p>
 *
 * <p>领域层不关心底层使用 Jev、Laya 或其他兼容 System One 协议的实现。
 * HTTP 请求、鉴权、超时、JSON 解析以及供应商差异全部由基础设施适配器负责。</p>
 */
public interface IToolOutcomeDecisionPort {

    /**
     * 判断一次工具执行结果是否偏离当前意图。
     *
     * <p>返回 {@link Optional#empty()} 不等价于“没有偏差”，而表示快速决策结果
     * 当前不能参与正式业务判断。调用方必须继续执行本地兜底规则。常见原因包括：</p>
     * <ol>
     *     <li>该能力未启用；</li>
     *     <li>当前处于影子模式，只观测结果而不影响业务；</li>
     *     <li>远程调用超时、失败或返回非 2xx 状态；</li>
     *     <li>响应结构不完整或返回了未知选项；</li>
     *     <li>选中答案的概率低于本地使用门槛。</li>
     * </ol>
     *
     * @param request 本次工具结果判断请求，包含用户目标、当前意图、工具信息和执行结果
     * @return 结果满足正式使用条件时返回结构化判断，否则返回 Optional.empty()
     */
    Optional<StructuredDecision<ToolOutcome>> assess(ToolOutcomeDecisionRequest request);

    /**
     * 工具结果的四种互斥结论。
     *
     * <p>{@link #EXECUTION_FAILED} 与 {@link #INTENT_MISMATCH} 必须严格区分：
     * 权限不足、网络不通、命令不存在等通常只是执行失败，并不能单独证明意图识别错误。</p>
     */
    enum ToolOutcome {

        /**
         * 工具成功执行，且结果与用户当前目标一致或能为该目标提供有效证据。
         */
        SUCCESS,

        /**
         * 工具或命令执行失败，但当前意图本身仍与用户目标一致。
         */
        EXECUTION_FAILED,

        /**
         * 工具选择、命令方向或返回内容表明当前意图分类很可能偏离用户真实目标。
         */
        INTENT_MISMATCH,

        /**
         * 当前信息不足，无法可靠地区分成功、执行失败和意图偏差。
         */
        INCONCLUSIVE
    }

    /**
     * 工具结果偏差判断请求。
     *
     * @param userMessage      用户本轮原始消息；用于判断工具行为是否符合用户真实目标
     * @param currentIntent    当前已经识别出的主意图，不允许为空
     * @param intentConfidence 当前意图的置信度，必须位于 0 到 1 之间
     * @param toolName         实际执行的工具名称；未知时使用空字符串
     * @param command          工具实际执行的命令或关键输入；非命令类工具可为空
     * @param reportedSuccess  工具协议返回的原始 success 字段，仅代表执行层是否成功
     * @param toolResult       工具返回的文本结果；允许为空，但空结果通常会被判为 INCONCLUSIVE
     */
    record ToolOutcomeDecisionRequest(
            String userMessage,
            IntentTypeEnumVO currentIntent,
            double intentConfidence,
            String toolName,
            String command,
            boolean reportedSuccess,
            String toolResult
    ) {

        /**
         * Record 紧凑构造器。
         *
         * <p>构造阶段只执行领域参数校验和 null 归一化。
         * 文本截断属于供应商调用策略，由基础设施适配器在构建请求时完成。</p>
         */
        public ToolOutcomeDecisionRequest {
            if (currentIntent == null) {
                throw new IllegalArgumentException("当前意图不能为空");
            }

            if (!Double.isFinite(intentConfidence)
                    || intentConfidence < 0.0
                    || intentConfidence > 1.0) {
                throw new IllegalArgumentException("当前意图置信度必须位于 0 到 1 之间");
            }

            /*
             * 这些文本可能来自不同工具实现。统一使用空字符串，
             * 避免基础设施层在构建 JSON 时反复处理 null。
             */
            userMessage = userMessage == null ? "" : userMessage;
            toolName = toolName == null ? "" : toolName;
            command = command == null ? "" : command;
            toolResult = toolResult == null ? "" : toolResult;
        }
    }

}
