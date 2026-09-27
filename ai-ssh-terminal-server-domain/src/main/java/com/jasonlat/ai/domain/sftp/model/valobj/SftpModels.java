package com.jasonlat.ai.domain.sftp.model.valobj;

import java.time.Instant;
import java.util.List;

/**
 * SFTP 领域使用的枚举、命令和不可变快照。
 *
 * <p>这些对象只描述文件元信息与任务状态，不携带 SSH 凭据、JSch 对象或文件正文。</p>
 */
public final class SftpModels {
    private SftpModels() {
    }

    /** 文件内容相对于服务器的传输方向。 */
    public enum Direction {
        /** 从客户端本地文件系统发送到服务器。 */
        UPLOAD,
        /** 从服务器读取并保存到客户端本地文件系统。 */
        DOWNLOAD
    }

    /** 文件系统条目类型。符号链接和特殊文件只展示，不参与首版传输。 */
    public enum Kind {
        FILE,
        DIRECTORY,
        SYMLINK,
        OTHER
    }

    /** 单个清单条目的生命周期状态。 */
    public enum ItemStatus {
        /** 等待客户端上传内容，或等待后端开始下载。 */
        PENDING,
        /** 后端正在传输该条目的字节内容。 */
        RUNNING,
        /** 下载内容已由后端发出，等待客户端确认落盘结果。 */
        SENT,
        /** 上传已提交，或下载已经客户端确认保存。 */
        COMPLETED,
        /** 因同名策略而跳过，视为已处理。 */
        SKIPPED,
        /** 当前条目失败，可以按任务规则重试。 */
        FAILED,
        /** 所属任务已取消。 */
        CANCELLED
    }

    /** 首版仅支持跳过或拒绝同名文件，避免不同服务器的覆盖语义导致旧文件丢失。 */
    public enum Conflict {
        /** 发现同名文件时停止，保留服务器上的原文件。 */
        FAIL,
        /** 跳过同名文件，继续处理清单中的其他条目。 */
        SKIP
    }

    /**
     * 服务器目录中的文件元信息。
     *
     * @param name        当前目录中的条目名称
     * @param path        服务器端规范化绝对路径
     * @param kind        文件系统条目类型
     * @param size        文件大小，单位为字节；目录通常为 0
     * @param modifiedAt  最后修改时间，Unix 毫秒时间戳
     * @param permissions 服务器返回的权限文本
     */
    public record Entry(
            String name,
            String path,
            Kind kind,
            long size,
            long modifiedAt,
            String permissions
    ) {
    }

    /**
     * 文件管理会话的公开快照。
     *
     * @param sftpSessionId 文件管理会话 ID
     * @param connectionId  会话引用的 SSH 连接 ID
     * @param rootPath      该会话允许访问的远程根目录
     * @param createdAt     会话创建时间
     */
    public record SessionView(
            String sftpSessionId,
            String connectionId,
            String rootPath,
            Instant createdAt
    ) {
    }

    /**
     * 经过领域层校验和目录补全后的传输清单条目。
     *
     * @param relativePath 清单根目录下的相对路径
     * @param kind         条目类型，只能是文件或目录
     * @param size         文件声明大小，单位为字节；目录必须为 0
     */
    public record ManifestItem(
            String relativePath,
            Kind kind,
            long size
    ) {
    }

    /**
     * 创建传输任务时交给领域服务的命令。
     *
     * @param sftpSessionId 文件管理会话 ID
     * @param direction     上传或下载方向
     * @param remotePath    上传目标目录或下载源路径
     * @param conflict      同名文件处理策略
     * @param items         上传清单；下载时由服务端扫描后生成
     */
    public record CreateTransfer(
            String sftpSessionId,
            Direction direction,
            String remotePath,
            Conflict conflict,
            List<ManifestItem> items
    ) {
    }

    /**
     * 单个传输条目的客户端视图。
     *
     * @param itemId           条目 ID
     * @param relativePath     相对于任务根目录的路径
     * @param kind             文件或目录类型
     * @param size             条目预期大小，单位为字节
     * @param transferredBytes 已确认传输的累计字节数
     * @param status           当前生命周期状态
     * @param errorCode        失败时的稳定业务错误码
     * @param message          失败或跳过原因
     */
    public record ItemView(
            String itemId,
            String relativePath,
            Kind kind,
            long size,
            long transferredBytes,
            ItemStatus status,
            String errorCode,
            String message
    ) {
    }

    /**
     * 任务级进度快照，由条目状态和累计字节实时汇总得到。
     *
     * @param transferId          传输任务 ID
     * @param direction           上传或下载方向
     * @param status              汇总后的任务状态
     * @param totalBytes          除跳过条目外的预期总字节数
     * @param transferredBytes    所有条目已传输字节数之和
     * @param completedItems      已完成或已跳过的条目数量
     * @param totalItems          清单条目总数，包含目录
     * @param failedItems         失败条目数量
     * @param awaitingConfirmation 已发送但仍等待客户端确认落盘的条目数量
     * @param updatedAt           最近一次状态或字节进度更新时间
     * @param activeItems         当前处于 RUNNING 状态的条目快照
     */
    public record Progress(
            String transferId,
            Direction direction,
            String status,
            long totalBytes,
            long transferredBytes,
            int completedItems,
            int totalItems,
            int failedItems,
            int awaitingConfirmation,
            Instant updatedAt,
            List<ItemView> activeItems
    ) {
    }

    /**
     * 传输任务完整快照。
     *
     * @param transferId    传输任务 ID
     * @param sftpSessionId 所属文件管理会话 ID
     * @param direction     上传或下载方向
     * @param remotePath    任务对应的远程基准路径
     * @param progress      任务级汇总进度
     * @param items         清单内全部条目的当前状态
     */
    public record TransferView(
            String transferId,
            String sftpSessionId,
            Direction direction,
            String remotePath,
            Progress progress,
            List<ItemView> items
    ) {
    }
}
