package com.jasonlat.ai.domain.sftp.model.valobj;

import java.time.Duration;

/**
 * 部署层装配的 SFTP 容量与超时策略；领域层不直接依赖 Spring 配置绑定或 JSch。
 *
 * @param maxSessions                   服务允许同时存在的会话总数
 * @param maxSessionsPerUser            单个用户允许同时存在的会话数
 * @param maxTransfers                  服务允许同时运行的文件传输总数
 * @param maxTransfersPerUser           单个用户允许同时运行的文件传输数
 * @param maxTransfersPerSession        单个文件管理会话允许同时运行的文件传输数
 * @param maxTasksPerSession            单个会话保留的任务数量上限
 * @param maxTrackedItems               服务内存中跟踪的清单条目总数上限
 * @param maxFiles                      单个任务允许包含的文件和目录条目数
 * @param maxDepth                      目录递归扫描和相对路径的最大深度
 * @param maxFileSize                   单个文件允许的最大字节数
 * @param maxTaskBytes                  单个任务允许的累计字节数
 * @param sessionIdleTimeout            无活动会话的自动回收时间
 * @param transferIdleTimeout           无字节进展传输的中止时间
 * @param taskRetention                 完成任务快照在内存中的保留时间
 * @param operationTimeout              普通目录操作及建连超时时间
 * @param progressPublishInterval       SSE 检查并发布进度快照的间隔
 */
public record SftpPolicy(
        int maxSessions,
        int maxSessionsPerUser,
        int maxTransfers,
        int maxTransfersPerUser,
        int maxTransfersPerSession,
        int maxTasksPerSession,
        int maxTrackedItems,
        int maxFiles,
        int maxDepth,
        long maxFileSize,
        long maxTaskBytes,
        Duration sessionIdleTimeout,
        Duration transferIdleTimeout,
        Duration taskRetention,
        Duration operationTimeout,
        Duration progressPublishInterval
) {
    public SftpPolicy {
        if (maxSessions < 1 || maxSessionsPerUser < 1 || maxTransfers < 1 || maxTransfersPerUser < 1
                || maxTransfersPerSession < 1 || maxTasksPerSession < 1
                || maxTrackedItems < 1 || maxFiles < 1 || maxDepth < 1
                || maxFileSize < 1 || maxTaskBytes < maxFileSize) {
            throw new IllegalArgumentException("SFTP 容量配置不合法");
        }
        Duration[] timeouts = {
                sessionIdleTimeout,
                transferIdleTimeout,
                taskRetention,
                operationTimeout
        };
        for (Duration timeout : timeouts) {
            if (timeout == null || timeout.toMillis() < 1) {
                throw new IllegalArgumentException("SFTP 超时不能低于 1ms");
            }
        }
        if (progressPublishInterval == null || progressPublishInterval.toMillis() < 100) {
            throw new IllegalArgumentException("SFTP 推送间隔不能低于 100ms");
        }
    }
}
