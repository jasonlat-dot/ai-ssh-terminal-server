package com.jasonlat.ai.domain.sftp.model.valobj;

import java.time.Instant;
import java.util.List;

/** 文件元信息和不可变快照；不携带 SSH 凭据、SDK 对象或文件正文。 */
public final class SftpModels {
    private SftpModels() { }
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum Kind { FILE, DIRECTORY, SYMLINK, OTHER }
    public enum ItemStatus { PENDING, RUNNING, SENT, COMPLETED, SKIPPED, FAILED, CANCELLED }
    /** 首版仅支持跳过或拒绝同名文件，避免不同服务器的覆盖语义导致旧文件丢失。 */
    public enum Conflict { FAIL, SKIP }
    public record Entry(String name, String path, Kind kind, long size, long modifiedAt, String permissions) { }
    public record SessionView(String sftpSessionId, String connectionId, String rootPath, Instant createdAt) { }
    /** relativePath 必须是清单根下的相对路径；目录 size 必须为 0。 */
    public record ManifestItem(String relativePath, Kind kind, long size) { }
    public record CreateTransfer(String sftpSessionId, Direction direction, String remotePath,
                                 Conflict conflict, List<ManifestItem> items) { }
    public record ItemView(String itemId, String relativePath, Kind kind, long size, long transferredBytes,
                           ItemStatus status, String errorCode, String message) { }
    public record Progress(String transferId, Direction direction, String status, long totalBytes,
                           long transferredBytes, int completedItems, int totalItems, int failedItems,
                           int awaitingConfirmation, Instant updatedAt, List<ItemView> activeItems) { }
    public record TransferView(String transferId, String sftpSessionId, Direction direction,
                               String remotePath, Progress progress, List<ItemView> items) { }
}
