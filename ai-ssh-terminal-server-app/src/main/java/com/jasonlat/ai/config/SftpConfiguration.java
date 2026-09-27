package com.jasonlat.ai.config;

import com.jasonlat.ai.cases.sftp.SftpServiceCase;
import com.jasonlat.ai.config.properties.SftpProperties;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpPolicy;
import com.jasonlat.ai.domain.sftp.service.ISftpService;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将配置转换为领域策略，并装配统一应用门面。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SftpProperties.class)
public class SftpConfiguration {

    /** 将 Spring 配置对象转换为不依赖框架的领域策略。 */
    @Bean
    public SftpPolicy sftpPolicy(SftpProperties properties) {
        return new SftpPolicy(
                properties.getMaxTotalSessions(),
                properties.getMaxSessionsPerUser(),
                properties.getMaxConcurrentTransfers(),
                properties.getMaxConcurrentTransfersPerUser(),
                properties.getMaxConcurrentTransfersPerSession(),
                properties.getMaxTasksPerSession(),
                properties.getMaxTrackedItems(),
                properties.getMaxFilesPerTask(),
                properties.getMaxDirectoryDepth(),
                properties.getMaxFileSize().toBytes(),
                properties.getMaxTaskSize().toBytes(),
                properties.getSessionIdleTimeout(),
                properties.getTransferIdleTimeout(),
                properties.getCompletedTaskRetention(),
                properties.getOperationTimeout(),
                properties.getProgressPublishInterval()
        );
    }

    @Bean
    public SftpServiceCase sftpServiceCase(
            ISftpService service,
            ISshConnectionRepository repository,
            SftpProperties properties
    ) {
        return new SftpServiceCase(service, repository, properties.getAnonymousUserId());
    }
}
