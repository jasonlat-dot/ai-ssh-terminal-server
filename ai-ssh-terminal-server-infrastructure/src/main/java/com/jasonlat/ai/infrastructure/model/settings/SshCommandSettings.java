package com.jasonlat.ai.infrastructure.model.settings;

/** SSH 命令执行参数，不负责 YAML 绑定；实例由 app 装配。 */
public record SshCommandSettings(long idleTimeoutSeconds, long maxExecutionTimeoutSeconds) {
    public SshCommandSettings {
        if (idleTimeoutSeconds <= 0 || maxExecutionTimeoutSeconds < idleTimeoutSeconds) {
            throw new IllegalArgumentException("SSH 命令超时参数不合法");
        }
    }

    /** 手工构造端口时使用的默认值。 */
    public static SshCommandSettings defaults() {
        return new SshCommandSettings(10, 600);
    }
}
