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
    @Bean public SftpPolicy sftpPolicy(SftpProperties p) {
        return new SftpPolicy(p.getMaxTotalSessions(), p.getMaxSessionsPerUser(), p.getMaxConcurrentTransfers(),
                p.getMaxConcurrentTransfersPerUser(), p.getMaxConcurrentTransfersPerSession(), p.getMaxTasksPerSession(),
                p.getMaxTrackedItems(), p.getMaxFilesPerTask(), p.getMaxDirectoryDepth(), p.getMaxFileSize().toBytes(),
                p.getMaxTaskSize().toBytes(), p.getSessionIdleTimeout(), p.getTransferIdleTimeout(),
                p.getCompletedTaskRetention(), p.getOperationTimeout(), p.getProgressPublishInterval());
    }
    @Bean public SftpServiceCase sftpServiceCase(ISftpService service, ISshConnectionRepository repository, SftpProperties p) {
        return new SftpServiceCase(service, repository, p.getAnonymousUserId());
    }
}
