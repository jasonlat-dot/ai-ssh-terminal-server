package com.jasonlat.ai.config;

import com.jasonlat.ai.config.properties.SshCommandProperties;
import com.jasonlat.ai.config.properties.SshHttpProxyProperties;
import com.jasonlat.ai.config.properties.TerminalSessionProperties;
import com.jasonlat.ai.infrastructure.model.settings.SshCommandSettings;
import com.jasonlat.ai.infrastructure.model.settings.SshHttpProxySettings;
import com.jasonlat.ai.infrastructure.model.settings.TerminalSessionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** app 负责读取部署配置并装配参数，基础设施层只依赖自己的参数类型。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        SshCommandProperties.class, SshHttpProxyProperties.class, TerminalSessionProperties.class
})
public class SshInfrastructureConfiguration {

    @Bean
    public SshCommandSettings sshCommandSettings(SshCommandProperties properties) {
        return new SshCommandSettings(
                properties.getIdleTimeoutSeconds(), properties.getMaxExecutionTimeoutSeconds());
    }

    @Bean
    public SshHttpProxySettings sshHttpProxySettings(SshHttpProxyProperties properties) {
        return new SshHttpProxySettings(properties.isEnabled(), properties.getHost(), properties.getPort(),
                properties.getUsername(), properties.getPassword());
    }

    @Bean
    public TerminalSessionSettings terminalSessionSettings(TerminalSessionProperties properties) {
        return new TerminalSessionSettings(properties.getMaxTotalSessions(), properties.getMaxSessionsPerUser(),
                properties.getMaxSessionsPerConnection(), properties.getIdleTimeoutMinutes(),
                properties.getCleanupIntervalMinutes(), properties.getTerminationRecordTtlMinutes(),
                properties.getMaxTerminationRecords());
    }
}
