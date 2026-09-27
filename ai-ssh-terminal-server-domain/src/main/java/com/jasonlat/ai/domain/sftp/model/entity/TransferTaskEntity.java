package com.jasonlat.ai.domain.sftp.model.entity;

import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import java.time.Instant;
import java.util.*;

/** 一个目录任务包含多个独立条目。状态与累计字节在同一把锁下更新，快照不暴露可变对象。 */
public final class TransferTaskEntity {
    /** 任务唯一 ID，由服务端生成。 */
    public final String id = UUID.randomUUID().toString();
    /** 创建该任务的文件管理会话 ID。 */
    public final String sessionId;
    /** 文件内容相对于服务器的传输方向。 */
    public final Direction direction;
    /** 上传目标目录或下载源条目的父路径。 */
    public final String remotePath;
    /** 上传遇到同名文件时采用的策略。 */
    public final Conflict conflict;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private volatile boolean cancelled;
    private Instant updatedAt = Instant.now();

    private static final class Item {
        final String id = UUID.randomUUID().toString();
        final ManifestItem source;
        long bytes;
        ItemStatus status = ItemStatus.PENDING;
        String code;
        String message;
        Item(ManifestItem source) {
            this.source = source;
        }

        ItemView view() {
            return new ItemView(
                    id,
                    source.relativePath(),
                    source.kind(),
                    source.size(),
                    bytes,
                    status,
                    code,
                    message
            );
        }
    }

    public TransferTaskEntity(String sessionId, Direction direction, String remotePath,
                              Conflict conflict, List<ManifestItem> manifest) {
        this.sessionId = sessionId;
        this.direction = direction;
        this.remotePath = remotePath;
        this.conflict = conflict;

        for (ManifestItem entry : manifest) {
            Item item = new Item(entry);
            items.put(item.id, item);
        }
    }

    public boolean cancelled() {
        return cancelled;
    }

    public int itemCount() {
        return items.size();
    }

    public synchronized ItemView item(String id) {
        return require(id).view();
    }

    private Item require(String id) {
        Item item = items.get(id);
        if (item == null) throw new SftpException("SFTP_NOT_FOUND", "传输条目不存在");
        return item;
    }
    /** 失败条目可重传；运行中和已成功条目不可重复提交。 */
    public synchronized ItemView begin(String id, Direction expected) {
        Item item = require(id);
        if (cancelled) {
            throw new SftpException("SFTP_CANCELLED", "任务已取消");
        }
        if (direction != expected || item.source.kind() != Kind.FILE) {
            throw new SftpException("SFTP_INVALID", "传输方向或条目类型不匹配");
        }
        if (item.status != ItemStatus.PENDING && item.status != ItemStatus.FAILED) {
            throw new SftpException("SFTP_CONFLICT", "条目不可重复执行");
        }

        // 重试失败条目时必须清空上一次的进度与错误，避免快照混入旧状态。
        item.bytes = 0;
        item.code = null;
        item.message = null;
        item.status = ItemStatus.RUNNING;
        updatedAt = Instant.now();
        return item.view();
    }
    public synchronized void progress(String id, long delta) {
        Item item = require(id);
        if (item.status == ItemStatus.RUNNING) {
            item.bytes = Math.min(item.source.size(), item.bytes + delta);
            updatedAt = Instant.now();
        }
    }
    public synchronized void finish(String id, ItemStatus status, String code, String message) {
        Item item = require(id);
        if (cancelled) return;
        item.status = status;
        item.code = code;
        item.message = message;
        updatedAt = Instant.now();
    }
    /** 下载 SENT 仅表示后端发送完成；Tauri 关闭本地文件成功后再确认。 */
    public synchronized void confirm(String id, boolean saved) {
        Item item = require(id);
        if (cancelled) {
            throw new SftpException("SFTP_CANCELLED", "任务已取消");
        }
        if (direction != Direction.DOWNLOAD) {
            throw new SftpException("SFTP_INVALID", "上传任务不接受本地保存确认");
        }
        if (item.status == ItemStatus.COMPLETED && saved) {
            return;
        }
        boolean retryDirectory = item.source.kind() == Kind.DIRECTORY && item.status == ItemStatus.FAILED;
        if (item.status != ItemStatus.SENT && !retryDirectory) {
            throw new SftpException("SFTP_CONFLICT", "条目尚未发送完成");
        }

        finish(
                id,
                saved ? ItemStatus.COMPLETED : ItemStatus.FAILED,
                saved ? null : "SFTP_LOCAL_SAVE_FAILED",
                saved ? null : "客户端本地保存失败"
        );
    }
    public synchronized void cancel() {
        boolean alreadyFinished = items.values().stream()
                .allMatch(item -> item.status == ItemStatus.COMPLETED
                        || item.status == ItemStatus.SKIPPED);
        if (alreadyFinished) {
            return;
        }

        cancelled = true;
        for (Item item : items.values()) {
            if (item.status != ItemStatus.COMPLETED && item.status != ItemStatus.SKIPPED) {
                item.status = ItemStatus.CANCELLED;
            }
        }
        updatedAt = Instant.now();
    }
    public synchronized Progress progress() {
        long total = 0, bytes = 0;
        int complete = 0, failed = 0, sent = 0, running = 0, pending = 0;
        for (Item item : items.values()) {
            if (item.status != ItemStatus.SKIPPED) total += item.source.size();
            bytes += item.bytes;
            switch (item.status) {
                case COMPLETED, SKIPPED -> complete++;
                case FAILED -> failed++;
                case SENT -> sent++;
                case RUNNING -> running++;
                case PENDING -> pending++;
                default -> {
                    // CANCELLED 不计入完成、失败或等待数量，由任务级 cancelled 状态单独表示。
                }
            }
        }
        String state = resolveState(complete, failed, running, pending);
        List<ItemView> runningItems = items.values().stream()
                .filter(item -> item.status == ItemStatus.RUNNING)
                .map(Item::view)
                .toList();

        return new Progress(
                id,
                direction,
                state,
                total,
                bytes,
                complete,
                items.size(),
                failed,
                sent,
                updatedAt,
                runningItems
        );
    }

    /** 按优先级归并条目状态，生成任务级状态。 */
    private String resolveState(int complete, int failed, int running, int pending) {
        if (cancelled) {
            return "CANCELLED";
        }
        if (complete == items.size()) {
            return "COMPLETED";
        }
        if (running > 0) {
            return "RUNNING";
        }
        if (pending > 0) {
            return "AWAITING_CONTENT";
        }
        if (failed > 0) {
            return "FAILED";
        }
        return "AWAITING_CONFIRMATION";
    }

    public synchronized TransferView snapshot() {
        List<ItemView> itemViews = items.values().stream()
                .map(Item::view)
                .toList();
        return new TransferView(id, sessionId, direction, remotePath, progress(), itemViews);
    }
}
