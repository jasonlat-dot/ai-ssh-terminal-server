package com.jasonlat.ai.infrastructure.model.settings;

/**
 * SSH 命令语义风险判断的独立运行参数。
 *
 * <p>URL、API Key、模型和超时复用 {@link SystemOneDecisionSettings}；本对象只保存
 * 命令风险能力自己的开关、影子模式、正式采用门槛和输入长度限制。</p>
 *
 * @param enabled                  是否调用 System One 判断 SSH 命令风险
 * @param shadowMode               是否只记录判断而不真正拦截命令
 * @param minAnswerProbability     正式采用风险结论的最低答案概率
 * @param maxCommandCharacters     允许发送给服务的最大命令字符数
 * @param maxUserMessageCharacters 允许发送给服务的最大任务描述字符数
 */
public record CommandRiskDecisionSettings(
        boolean enabled,
        boolean shadowMode,
        double minAnswerProbability,
        int maxCommandCharacters,
        int maxUserMessageCharacters
) {

    /** 在应用启动阶段校验全部参数。 */
    public CommandRiskDecisionSettings {
        if (!Double.isFinite(minAnswerProbability)
                || minAnswerProbability < 0.0
                || minAnswerProbability > 1.0) {
            throw new IllegalArgumentException("命令风险 minAnswerProbability 必须位于 0 到 1 之间");
        }
        if (maxCommandCharacters < 64) {
            throw new IllegalArgumentException("命令风险 maxCommandCharacters 不能小于 64");
        }
        if (maxUserMessageCharacters < 128) {
            throw new IllegalArgumentException("命令风险 maxUserMessageCharacters 不能小于 128");
        }
    }
}
