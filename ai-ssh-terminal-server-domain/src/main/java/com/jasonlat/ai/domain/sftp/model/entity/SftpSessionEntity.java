package com.jasonlat.ai.domain.sftp.model.entity;

import com.jasonlat.ai.domain.sftp.adapter.port.ISftpClientPort;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.SessionView;
import java.time.Instant;
import java.util.UUID;

/** 文件管理窗口的独立会话，连接和生命周期字段由服务统一管理。 */
public final class SftpSessionEntity {
    public final String id = UUID.randomUUID().toString();
    public final String ownerId;
    public final String connectionId;
    public final Instant createdAt = Instant.now();
    public volatile long lastActiveAt = System.currentTimeMillis();
    public volatile ISftpClientPort.Connection connection;
    public volatile String rootPath;
    public volatile boolean closed;
    public SftpSessionEntity(String ownerId, String connectionId) { this.ownerId = ownerId; this.connectionId = connectionId; }
    public SessionView view() { return new SessionView(id, connectionId, rootPath, createdAt); }
}
