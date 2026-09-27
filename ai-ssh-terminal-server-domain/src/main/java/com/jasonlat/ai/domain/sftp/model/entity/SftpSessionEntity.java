package com.jasonlat.ai.domain.sftp.model.entity;

import com.jasonlat.ai.domain.sftp.adapter.port.ISftpClientPort;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.SessionView;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件管理窗口对应的独立 SFTP 会话。
 *
 * <p>连接引用、根目录和生命周期字段只允许由 {@code SftpService} 统一管理。
 * volatile 保证清理线程、请求线程和传输线程能够及时观察到状态变化。</p>
 */
public final class SftpSessionEntity {
    /** 文件管理会话唯一 ID。 */
    public final String id = UUID.randomUUID().toString();
    /** 会话所属用户，所有后续操作都必须再次校验该字段。 */
    public final String ownerId;
    /** 会话引用的已保存 SSH 连接 ID。 */
    public final String connectionId;
    /** 会话创建时间。 */
    public final Instant createdAt = Instant.now();
    /** 最近一次有效目录操作或传输进展的时间戳。 */
    public volatile long lastActiveAt = System.currentTimeMillis();
    /** 独立的底层 SSH/SFTP 连接。 */
    public volatile ISftpClientPort.Connection connection;
    /** 规范化后的远程访问根目录。 */
    public volatile String rootPath;
    /** 会话是否已经进入关闭状态。 */
    public volatile boolean closed;
    public SftpSessionEntity(String ownerId, String connectionId) {
        this.ownerId = ownerId;
        this.connectionId = connectionId;
    }

    /** 返回不包含底层连接对象的只读会话视图。 */
    public SessionView view() {
        return new SessionView(id, connectionId, rootPath, createdAt);
    }
}
