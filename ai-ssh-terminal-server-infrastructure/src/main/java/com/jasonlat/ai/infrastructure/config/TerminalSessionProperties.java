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

    /** 没有前端读写后，终端最多保留的分钟数。 */
    private long idleTimeoutMinutes = 30;

    /** 后台清理任务的执行间隔（分钟）。 */
    private long cleanupIntervalMinutes = 5;

    @Override
    public void afterPropertiesSet() {
        requirePositive(maxTotalSessions, "max-total-sessions");
        requirePositive(maxSessionsPerUser, "max-sessions-per-user");
        requirePositive(maxSessionsPerConnection, "max-sessions-per-connection");
        requirePositive(idleTimeoutMinutes, "idle-timeout-minutes");
        requirePositive(cleanupIntervalMinutes, "cleanup-interval-minutes");
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("ai.ssh.terminal." + name + " 必须大于 0");
        }
    }
}
