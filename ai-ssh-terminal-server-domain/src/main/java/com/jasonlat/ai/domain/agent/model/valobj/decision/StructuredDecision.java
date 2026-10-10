package com.jasonlat.ai.domain.agent.model.valobj.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化单选决策的供应商无关领域结果。
 *
 * <p>意图识别、工具结果判断和 SSH 命令风险判断虽然选择的业务枚举不同，
 * 但都遵循相同的返回语义：从一组互斥候选项中选择一个答案，并同时返回
 * 答案概率、供应商原生置信度、完整概率分布以及可观测信息。</p>
 *
 * <p>该值对象位于领域层，因此不依赖 Jev、Laya 或 System One 的 HTTP
 * 响应类型。基础设施适配器负责把供应商响应转换为本对象，领域服务只读取
 * 统一后的结果。</p>
 *
 * @param choice             最终选中的领域枚举值，例如某个意图、工具结果或风险等级
 * @param answerProbability  {@code probabilities[choice]} 对应的答案概率，用于统一业务门槛
 * @param nativeConfidence   供应商原生 confidence，仅用于日志、观测和离线校准
 * @param probabilities      所有合法候选项对应的完整概率分布
 * @param provider           实际使用的供应商标识，例如 jev 或 laya
 * @param model              服务端实际返回的模型名称
 * @param rawResponse        供应商原始响应，仅供调试和离线校准，不能写入普通日志
 * @param <C>                当前决策使用的候选枚举类型
 */
public record StructuredDecision<C extends Enum<C>>(
        C choice,
        double answerProbability,
        double nativeConfidence,
        Map<C, Double> probabilities,
        String provider,
        String model,
        String rawResponse
) {

    /**
     * 校验公共决策字段并冻结概率分布。
     *
     * <p>所有决策端口通过同一个构造器执行这些不变量检查，避免每新增一种
     * System One 用法就复制一遍概率校验、Map 防御性复制和 null 归一化代码。</p>
     */
    public StructuredDecision {
        if (choice == null) {
            throw new IllegalArgumentException("结构化决策的选中项不能为空");
        }

        if (!Double.isFinite(answerProbability)
                || answerProbability < 0.0
                || answerProbability > 1.0) {
            throw new IllegalArgumentException("结构化决策的答案概率必须位于 0 到 1 之间");
        }

        /*
         * LinkedHashMap 保留供应商返回的候选顺序，便于日志和离线分析。
         * 不可修改包装阻止调用方在结果产生后篡改概率分布。
         */
        probabilities = probabilities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));

        /*
         * 可观测字段允许供应商不返回，但领域对象内部始终使用空字符串，
         * 从而避免每个调用方重复处理 null。
         */
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        rawResponse = rawResponse == null ? "" : rawResponse;
    }
}
