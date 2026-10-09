package com.jasonlat.ai.config;

import com.jasonlat.ai.config.properties.DecisionProviderProperties;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import com.jasonlat.ai.infrastructure.model.settings.ToolOutcomeDecisionSettings;
import com.jasonlat.ai.infrastructure.model.settings.ToolSelectionDecisionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;

/**
 * Jev/Laya System One 配置装配类。
 *
 * <p>app 模块负责读取部署配置，随后转换成基础设施层自己的设置对象。
 * 基础设施模块因此不需要直接依赖 ConfigurationProperties。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DecisionProviderProperties.class)
public class DecisionProviderConfiguration {

    /**
     * 创建 System One 适配器使用的运行参数。
     *
     * @param properties Spring Boot 从 ai.decision 读取的外部配置
     * @return 已完成类型转换的基础设施运行参数
     */
    @Bean
    public SystemOneDecisionSettings systemOneDecisionSettings(DecisionProviderProperties properties) {
        return new SystemOneDecisionSettings(
                properties.isEnabled(),
                properties.isShadowMode(),
                properties.getUrl(),
                properties.getApiKey(),
                properties.getModel(),
                properties.getTimeout(),
                properties.getMinAnswerProbability()
        );
    }

    /**
     * 创建工具结果与意图偏差判断能力的独立运行参数。
     *
     * <p>供应商连接信息由 {@link #systemOneDecisionSettings} 统一提供；
     * 这里仅装配该子能力自己的开关、影子模式、概率门槛和结果长度限制。</p>
     *
     * @param properties Spring Boot 从 {@code ai.decision.tool-outcome} 读取的配置
     * @return 工具结果判断适配器使用的运行参数
     */
    @Bean
    public ToolOutcomeDecisionSettings toolOutcomeDecisionSettings(DecisionProviderProperties properties) {
        DecisionProviderProperties.ToolOutcomeProperties toolOutcome = properties.getToolOutcome();

        return new ToolOutcomeDecisionSettings(
                toolOutcome.isEnabled(),
                toolOutcome.isShadowMode(),
                toolOutcome.getMinAnswerProbability(),
                toolOutcome.getMaxResultCharacters()
        );
    }

    /**
     * 创建每轮工具筛选能力的独立运行参数。
     *
     * @param properties Spring Boot 从 {@code ai.decision.tool-selection} 读取的配置
     * @return 工具筛选适配器使用的不可变参数
     */
    @Bean
    public ToolSelectionDecisionSettings toolSelectionDecisionSettings(
            DecisionProviderProperties properties
    ) {
        DecisionProviderProperties.ToolSelectionProperties toolSelection =
                properties.getToolSelection();

        return new ToolSelectionDecisionSettings(
                toolSelection.isEnabled(),
                toolSelection.isShadowMode(),
                toolSelection.getMinAnswerProbability(),
                toolSelection.getMinRetainedTools(),
                toolSelection.getMaxToolsPerRequest(),
                toolSelection.getMaxDescriptionCharacters(),
                toolSelection.getMaxContextCharacters(),
                new LinkedHashSet<>(toolSelection.getAlwaysKeepTools())
        );
    }
}
