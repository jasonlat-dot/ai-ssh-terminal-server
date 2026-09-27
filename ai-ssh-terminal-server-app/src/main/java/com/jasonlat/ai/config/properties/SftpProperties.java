package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import java.time.Duration;

/** SFTP 独立会话、任务和流式传输限制。所有部署参数集中在 app 层。 */
@Data
@ConfigurationProperties(prefix = "ai.ssh.sftp")
public class SftpProperties {

    /** 服务允许同时保留的独立 SSH/SFTP 会话总数。 */
    private int maxTotalSessions = 50;
    /** 单个用户允许同时保留的文件管理会话数。 */
    private int maxSessionsPerUser = 5;

    /** 服务允许同时读写文件内容的传输总数。 */
    private int maxConcurrentTransfers = 20;
    /** 单个用户允许同时运行的文件传输数。 */
    private int maxConcurrentTransfersPerUser = 4;
    /** 单个文件管理会话允许同时运行的文件传输数。 */
    private int maxConcurrentTransfersPerSession = 2;

    /** 单个会话保留的传输任务数量上限。 */
    private int maxTasksPerSession = 20;
    /** 所有任务在内存中跟踪的清单条目总数上限。 */
    private int maxTrackedItems = 100000;
    /** 单个传输任务允许包含的文件与目录数量。 */
    private int maxFilesPerTask = 10000;
    /** 目录递归扫描与相对路径允许的最大深度。 */
    private int maxDirectoryDepth = 64;

    /** 单个文件允许传输的最大大小。 */
    private DataSize maxFileSize = DataSize.ofGigabytes(10);
    /** 单个任务内所有文件允许的累计最大大小。 */
    private DataSize maxTaskSize = DataSize.ofGigabytes(100);

    /** 没有活动操作的会话自动回收时间。 */
    private Duration sessionIdleTimeout = Duration.ofMinutes(15);
    /** 没有新增字节进度的传输中止时间。 */
    private Duration transferIdleTimeout = Duration.ofMinutes(2);
    /** 已完成任务快照在内存中的保留时间。 */
    private Duration completedTaskRetention = Duration.ofMinutes(30);
    /** SSH 建连和普通目录操作的超时时间。 */
    private Duration operationTimeout = Duration.ofSeconds(30);
    /** SSE 检查并发布任务进度快照的间隔。 */
    private Duration progressPublishInterval = Duration.ofMillis(300);

    /** 现有本地开发模式固定映射为 default；生产设置为空并接入服务端认证。 */
    private String anonymousUserId = "default";
}
