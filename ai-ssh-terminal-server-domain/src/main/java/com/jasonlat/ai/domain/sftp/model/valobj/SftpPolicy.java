package com.jasonlat.ai.domain.sftp.model.valobj;

import java.time.Duration;

/** 部署层装配的容量策略；领域不依赖配置绑定或 JSch。 */
public record SftpPolicy(int maxSessions, int maxSessionsPerUser, int maxTransfers,
                         int maxTransfersPerUser, int maxTransfersPerSession,
                         int maxTasksPerSession, int maxTrackedItems, int maxFiles, int maxDepth, long maxFileSize,
                         long maxTaskBytes, Duration sessionIdleTimeout, Duration transferIdleTimeout,
                         Duration taskRetention, Duration operationTimeout, Duration progressPublishInterval) {
    public SftpPolicy {
        if (maxSessions < 1 || maxSessionsPerUser < 1 || maxTransfers < 1 || maxTransfersPerUser < 1
                || maxTransfersPerSession < 1 || maxTasksPerSession < 1 || maxTrackedItems < 1 || maxFiles < 1 || maxDepth < 1
                || maxFileSize < 1 || maxTaskBytes < maxFileSize) {
            throw new IllegalArgumentException("SFTP 容量配置不合法");
        }
        for (Duration d : new Duration[]{sessionIdleTimeout, transferIdleTimeout, taskRetention, operationTimeout}) {
            if (d == null || d.toMillis() < 1) throw new IllegalArgumentException("SFTP 超时不能低于 1ms");
        }
        if (progressPublishInterval == null || progressPublishInterval.toMillis() < 100) throw new IllegalArgumentException("SFTP 推送间隔不能低于 100ms");
    }
}
