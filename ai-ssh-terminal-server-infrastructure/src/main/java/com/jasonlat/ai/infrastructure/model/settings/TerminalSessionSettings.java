package com.jasonlat.ai.infrastructure.model.settings;

/** 终端容量、回收及终止记录参数的不可变快照。 */
public record TerminalSessionSettings(
        int maxTotalSessions,
        int maxSessionsPerUser,
        int maxSessionsPerConnection,
        long idleTimeoutMinutes,
        long cleanupIntervalMinutes,
        long terminationRecordTtlMinutes,
        int maxTerminationRecords) {

    public TerminalSessionSettings {
        if (maxTotalSessions <= 0 || maxSessionsPerUser <= 0 || maxSessionsPerConnection <= 0
                || idleTimeoutMinutes <= 0 || cleanupIntervalMinutes <= 0
                || terminationRecordTtlMinutes <= 0 || maxTerminationRecords <= 0) {
            throw new IllegalArgumentException("终端容量和时间参数必须大于 0");
        }
    }
}
