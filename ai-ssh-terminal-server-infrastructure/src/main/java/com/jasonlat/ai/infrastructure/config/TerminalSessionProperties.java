package com.jasonlat.ai.infrastructure.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SSH 终端容量和空闲回收配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.ssh.terminal")
public class TerminalSessionProperties implements InitializingBean {

    /** 整个应用允许同时存在的终端数。 */
    private int maxTotalSessions = 500;

    /** 单个用户允许同时存在的终端数。 */
    private int maxSessionsPerUser = 50;

    /** 单个 SSH connectionId 允许同时存在的终端数。 */
    private int maxSessionsPerConnection = 8;

    /** 没有终端输入、Agent 命令或 resize 后，终端最多保留的分钟数。 */
    private long idleTimeoutMinutes = 30;

    /** 后台清理任务的执行间隔（分钟）。 */
    private long cleanupIntervalMinutes = 5;

    /** 已结束终端的断开原因保留时间（分钟）。 */
    private long terminationRecordTtlMinutes = 5;

    /** 最多保留多少条终端结束原因，避免短时间大量重连占满内存。 */
    private int maxTerminationRecords = 1000;

    @Override
    public void afterPropertiesSet() {
        requirePositive(maxTotalSessions, "max-total-sessions");
        requirePositive(maxSessionsPerUser, "max-sessions-per-user");
        requirePositive(maxSessionsPerConnection, "max-sessions-per-connection");
        requirePositive(idleTimeoutMinutes, "idle-timeout-minutes");
        requirePositive(cleanupIntervalMinutes, "cleanup-interval-minutes");
        requirePositive(terminationRecordTtlMinutes, "termination-record-ttl-minutes");
        requirePositive(maxTerminationRecords, "max-termination-records");
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("ai.ssh.terminal." + name + " 必须大于 0");
        }
    }
}
