package com.jasonlat.ai.config;

import com.jasonlat.ai.config.properties.DecisionProviderProperties;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * @return 已选择并完成类型转换的基础设施运行参数
     */
    @Bean
    public SystemOneDecisionSettings systemOneDecisionSettings(DecisionProviderProperties properties) {
        /*
         * 根据 ai.decision.provider 选择 Jev 或 Laya 配置。
         * 未选中的供应商配置不会参与当前 Bean 的创建。
         */
        DecisionProviderProperties.EndpointProperties selected = properties.selected();

        /*
         * app 层与 infrastructure 层各自维护供应商枚举，
         * 通过相同枚举名称进行转换，避免模块反向依赖。
         */
        SystemOneDecisionSettings.Provider provider = SystemOneDecisionSettings.Provider.valueOf(properties.getProvider().name());

        return new SystemOneDecisionSettings(
                properties.isEnabled(),
                properties.isShadowMode(),
                provider,
                selected.getUrl(),
                selected.getApiKey(),
                selected.getModel(),
                selected.getTimeout(),
                selected.getMinAnswerProbability()
        );
    }
}