package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import java.time.Duration;

/** SFTP 独立会话、任务和流式传输限制。所有部署参数集中在 app 层。 */
@Data
@ConfigurationProperties(prefix = "ai.ssh.sftp")
public class SftpProperties {
    private int maxTotalSessions = 50;
    private int maxSessionsPerUser = 5;
    private int maxConcurrentTransfers = 20;
    private int maxConcurrentTransfersPerUser = 4;
    private int maxConcurrentTransfersPerSession = 2;
    private int maxTasksPerSession = 20;
    private int maxTrackedItems = 100000;
    private int maxFilesPerTask = 10000;
    private int maxDirectoryDepth = 64;
    private DataSize maxFileSize = DataSize.ofGigabytes(10);
    private DataSize maxTaskSize = DataSize.ofGigabytes(100);
    private Duration sessionIdleTimeout = Duration.ofMinutes(15);
    private Duration transferIdleTimeout = Duration.ofMinutes(2);
    private Duration completedTaskRetention = Duration.ofMinutes(30);
    private Duration operationTimeout = Duration.ofSeconds(30);
    private Duration progressPublishInterval = Duration.ofMillis(300);
    /** 现有本地开发模式固定映射为 default；生产设置为空并接入服务端认证。 */
    private String anonymousUserId = "default";
}
