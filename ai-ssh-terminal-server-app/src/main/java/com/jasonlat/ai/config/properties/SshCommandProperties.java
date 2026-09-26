package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 在交互式 SSH 终端中执行命令时使用的超时配置。
 */
@Data
@ConfigurationProperties(prefix = "ai.ssh.command")
public class SshCommandProperties implements InitializingBean {

    /** 当前命令连续没有产生输出时允许等待的秒数。 */
    private long idleTimeoutSeconds = 10;

    /** 当前命令即使持续产生输出也不能超过的绝对执行秒数。 */
    private long maxExecutionTimeoutSeconds = 600;

    @Override
    public void afterPropertiesSet() {
        if (idleTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("ai.ssh.command.idle-timeout-seconds 必须大于 0");
        }
        if (maxExecutionTimeoutSeconds < idleTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "ai.ssh.command.max-execution-timeout-seconds 不能小于 idle-timeout-seconds");
        }
    }
}
