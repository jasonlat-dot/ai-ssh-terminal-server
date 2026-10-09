package com.jasonlat.ai.infrastructure.model.settings;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 每轮工具筛选能力的独立运行参数。
 *
 * <p>URL、API Key、模型和请求超时继续复用 {@link SystemOneDecisionSettings}；
 * 本对象只保存工具筛选自己的灰度开关、安全门槛和输入规模限制。</p>
 *
 * @param enabled                  是否调用 System One 进行工具筛选
 * @param shadowMode               是否只记录结果但不修改 LlmRequest 工具集合
 * @param minAnswerProbability     允许正式采用 USE/SKIP 结论的最低答案概率
 * @param minRetainedTools         无论筛选结果如何都至少保留的工具数量
 * @param maxToolsPerRequest       单次最多交给 System One 判断的工具数量；溢出工具直接保留
 * @param maxDescriptionCharacters 每个工具说明允许发送的最大字符数
 * @param maxContextCharacters     最近调用上下文允许发送的最大字符数
 * @param alwaysKeepTools          永远保留、不交给 System One 过滤的工具名称
 */
public record ToolSelectionDecisionSettings(
        boolean enabled,
        boolean shadowMode,
        double minAnswerProbability,
        int minRetainedTools,
        int maxToolsPerRequest,
        int maxDescriptionCharacters,
        int maxContextCharacters,
        Set<String> alwaysKeepTools
) {

    /**
     * 校验安全参数并冻结永久保留名单。
     */
    public ToolSelectionDecisionSettings {
        if (!Double.isFinite(minAnswerProbability)
                || minAnswerProbability < 0.0
                || minAnswerProbability > 1.0) {
            throw new IllegalArgumentException("工具筛选 minAnswerProbability 必须位于 0 到 1 之间");
        }

        if (minRetainedTools < 1) {
            throw new IllegalArgumentException("工具筛选 minRetainedTools 不能小于 1");
        }

        if (maxToolsPerRequest < 1) {
            throw new IllegalArgumentException("工具筛选 maxToolsPerRequest 不能小于 1");
        }

        if (maxDescriptionCharacters < 64) {
            throw new IllegalArgumentException("工具筛选 maxDescriptionCharacters 不能小于 64");
        }

        if (maxContextCharacters < 256) {
            throw new IllegalArgumentException("工具筛选 maxContextCharacters 不能小于 256");
        }

        Set<String> normalized = new LinkedHashSet<>();

        if (alwaysKeepTools != null) {
            alwaysKeepTools.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }

        alwaysKeepTools = Collections.unmodifiableSet(normalized);
    }

    /**
     * 判断工具是否位于永久保留名单中，名称比较忽略大小写。
     *
     * @param toolName 工具名称
     * @return true 表示该工具不参与过滤
     */
    public boolean mustKeep(String toolName) {
        return toolName != null
                && alwaysKeepTools.stream().anyMatch(name -> name.equalsIgnoreCase(toolName));
    }
}
