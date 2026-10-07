package com.jasonlat.ai.domain.agent.adapter.port;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 快速结构化意图决策端口。
 *
 * <p>该接口位于领域层，用于描述 Agent 需要的“结构化意图决策能力”。
 * 领域层只关心输入消息和输出意图，不关心底层具体使用 Jev、Laya，
 * 或其他兼容 System One 协议的实现。</p>
 *
 * <p>具体的 HTTP 调用、API Key、超时控制、JSON 序列化和供应商差异，
 * 由基础设施层的适配器实现。</p>
 */
public interface IIntentDecisionPort {

    /**
     * 对用户输入执行一次结构化意图分类。
     *
     * <p>返回 {@link Optional#empty()} 表示当前快速分类结果不能用于正式路由，
     * 调用方应继续执行原有 LLM 分类流程。以下情况都会返回空：</p>
     * <ol>
     *     <li>System One 功能未启用；</li>
     *     <li>当前处于 shadow mode，只记录结果而不接管路由；</li>
     *     <li>Jev/Laya 调用超时或网络失败；</li>
     *     <li>服务返回非 2xx 状态码；</li>
     *     <li>响应 JSON 缺少必要字段；</li>
     *     <li>返回了系统不认识的意图；</li>
     *     <li>答案概率未达到本地配置的最低门槛。</li>
     * </ol>
     *
     * @param request 本次意图分类请求，包含用户消息、对话上下文和候选意图说明
     * @return 达到使用条件时返回结构化意图结果，否则返回 Optional.empty()
     */
    Optional<IntentDecision> classify(IntentDecisionRequest request);

    /**
     * 快速结构化意图分类请求。
     *
     * @param message  用户本轮输入的原始消息，不允许为空
     * @param context  最近意图和进行中任务等对话上下文；没有上下文时使用“无”
     * @param criteria 可供 System One 选择的意图及其简短语义说明，至少需要两个选项
     */
    record IntentDecisionRequest(
            String message,
            String context,
            Map<IntentTypeEnumVO, String> criteria
    ) {

        /**
         * Record 紧凑构造器。
         *
         * <p>在请求进入基础设施层前完成基本参数校验，并对候选项 Map
         * 做防御性复制，避免调用过程中被外部线程修改。</p>
         */
        public IntentDecisionRequest {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("意图分类消息不能为空");
            }

            // System One 需要明确的上下文值，避免把 null 直接写入请求。
            context = context == null || context.isBlank() ? "无" : context;

            if (criteria == null || criteria.size() < 2) {
                throw new IllegalArgumentException("意图分类至少需要两个候选项");
            }

            /*
             * LinkedHashMap 保留候选项插入顺序。
             * 不可修改包装保证调用期间候选项集合保持稳定。
             */
            criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        }
    }

    /**
     * 快速结构化意图分类结果。
     *
     * @param intent             最终选中的意图
     * @param answerProbability  被选中意图在 probabilities 中的概率
     * @param nativeConfidence   Jev/Laya 原始 confidence，仅用于观测，不用于跨供应商统一门槛
     * @param probabilities      每个合法意图对应的完整概率分布
     * @param provider           实际使用的供应商标识，例如 jev 或 laya
     * @param model              供应商返回的实际模型名称
     * @param rawResponse        供应商返回的原始 JSON，供问题排查使用
     */
    record IntentDecision(
            IntentTypeEnumVO intent,
            double answerProbability,
            double nativeConfidence,
            Map<IntentTypeEnumVO, Double> probabilities,
            String provider,
            String model,
            String rawResponse
    ) {

        /**
         * Record 紧凑构造器。
         *
         * <p>确保领域层收到的结果始终包含合法意图和有效答案概率。</p>
         */
        public IntentDecision {
            if (intent == null) {
                throw new IllegalArgumentException("意图分类结果不能为空");
            }

            if (!Double.isFinite(answerProbability) || answerProbability < 0.0 || answerProbability > 1.0) {
                throw new IllegalArgumentException("答案概率必须位于 0 到 1 之间");
            }

            /*
             * 响应概率做防御性复制。
             * 即使基础设施层后续复用了原 Map，也不能修改已经产生的领域结果。
             */
            probabilities = probabilities == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));

            // 可观测字段允许为空，但领域对象内部统一使用空字符串而不是 null。
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            rawResponse = rawResponse == null ? "" : rawResponse;
        }
    }
}