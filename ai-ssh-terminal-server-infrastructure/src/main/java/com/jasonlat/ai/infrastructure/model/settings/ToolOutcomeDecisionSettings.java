package com.jasonlat.ai.infrastructure.model.settings;

/**
 * 工具结果与意图偏差判断能力的独立运行参数。
 *
 * <p>供应商地址、密钥、模型和超时继续复用 {@link SystemOneDecisionSettings}。
 * 本对象只保存该业务能力自己的开关、影子模式、接受门槛和文本长度限制，
 * 使意图识别与工具结果判断可以分别灰度上线。</p>
 *
 * @param enabled               是否启用工具结果结构化判断
 * @param shadowMode            是否仅调用并记录，而不影响现有反馈回路
 * @param minAnswerProbability  被选中答案的最低使用概率
 * @param maxResultCharacters   发送给 Jev/Laya 的工具结果最大字符数
 */
public record ToolOutcomeDecisionSettings(
        boolean enabled,
        boolean shadowMode,
        double minAnswerProbability,
        int maxResultCharacters
) {

    /**
     * Record 紧凑构造器。
     *
     * <p>配置错误在应用启动阶段直接暴露，避免收到工具结果后才出现不可预测行为。</p>
     */
    public ToolOutcomeDecisionSettings {
        if (!Double.isFinite(minAnswerProbability)
                || minAnswerProbability < 0.0
                || minAnswerProbability > 1.0) {
            throw new IllegalArgumentException("工具结果判断 minAnswerProbability 必须位于 0 到 1 之间");
        }

        if (maxResultCharacters < 256) {
            throw new IllegalArgumentException("工具结果判断 maxResultCharacters 不能小于 256");
        }
    }
}
