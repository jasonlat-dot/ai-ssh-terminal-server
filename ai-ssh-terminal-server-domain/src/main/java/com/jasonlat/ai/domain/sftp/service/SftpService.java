package com.jasonlat.ai.domain.sftp.service;

import com.jasonlat.ai.domain.sftp.adapter.port.ISftpClientPort;
import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.entity.*;
import com.jasonlat.ai.domain.sftp.model.valobj.*;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件管理用例的领域实现。注册表锁只保护配额和生命周期，不包住网络读写。
 * 任务保存在有容量和保留期限制的内存表中，应用重启后客户端需重新创建会话与任务。
 */
@Slf4j
@Service
public class SftpService implements ISftpService {
    private final ISftpClientPort client;
    private final SftpPolicy policy;
    private final Object registryLock = new Object();
    private final Map<String, SftpSessionEntity> sessions = new ConcurrentHashMap<>();
    private final Map<String, TransferTaskEntity> tasks = new ConcurrentHashMap<>();
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();

    public SftpService(ISftpClientPort client, SftpPolicy policy) { this.client = client; this.policy = policy; }

    public SessionView open(String owner, SshConnectionEntity credentials, SshConnectionConfigEntity config, String root) {
        SftpSessionEntity session = new SftpSessionEntity(owner, credentials.getConnectionId());
        synchronized (registryLock) {
            if (sessions.size() >= policy.maxSessions()
                    || sessions.values().stream().filter(s -> s.ownerId.equals(owner)).count() >= policy.maxSessionsPerUser()) {
                throw error("SFTP_BUSY", "文件管理会话容量已满");
            }
            // 先占用名额，阻止并发建连绕过容量限制。
            sessions.put(session.id, session);
        }
        ISftpClientPort.Connection connection = null;
        try {
            connection = client.connect(credentials, config);
            synchronized (registryLock) {
                if (session.closed) throw error("SFTP_CANCELLED", "会话已关闭");
                session.connection = connection;
            }
            try (Operation operation = operation(session, null)) {
                String realRoot = operation.channel.realpath(root == null || root.isBlank() ? "." : RemotePath.absolute(root));
                Entry entry = operation.channel.stat(realRoot);
                if (entry == null || entry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "文件管理根路径必须是目录");
                session.rootPath = RemotePath.absolute(realRoot);
            }
            return session.view();
        } catch (RuntimeException e) {
            closeInternal(session);
            if (connection != null) connection.close();
            throw e;
        }
    }

    public SessionView session(String owner, String id) { return requireSession(owner, id).view(); }
    public void close(String owner, String id) { closeInternal(requireSession(owner, id)); }
    public List<Entry> list(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        try (Operation op = operation(session, null)) {
            String target = existing(op.channel, session, path == null ? session.rootPath : path);
            Entry entry = op.channel.stat(target);
            if (entry == null || entry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "只能浏览目录");
            return op.channel.list(target, policy.maxFiles());
        }
    }
    public void mkdir(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        try (Operation op = operation(session, null)) { ensureDirectory(op.channel, session, RemotePath.within(session.rootPath, path)); }
    }

    public TransferView create(String owner, CreateTransfer command) {
        if (command == null || command.direction() == null || command.remotePath() == null) throw error("SFTP_INVALID", "传输参数不完整");
        SftpSessionEntity session = requireSession(owner, command.sftpSessionId());
        try (Operation op = operation(session, null)) {
            checkTaskCapacity(session.id);
            String selected = existing(op.channel, session, command.remotePath());
            Entry selectedEntry = op.channel.stat(selected);
            if (selectedEntry == null) throw error("SFTP_NOT_FOUND", "目标在创建任务期间被移除");
            List<ManifestItem> manifest;
            String base;
            if (command.direction() == Direction.UPLOAD) {
                if (selectedEntry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "上传目标必须是目录");
                manifest = normalizeManifest(command.items());
                base = selected;
            } else {
                if (selected.equals("/")) throw error("SFTP_INVALID", "请选取一个具体目录或文件下载");
                List<ManifestItem> scanned = new ArrayList<>();
                scan(op, selected, RemotePath.name(selected), scanned, 1);
                manifest = normalizeManifest(scanned);
                base = RemotePath.parent(selected);
            }
            TransferTaskEntity task = new TransferTaskEntity(session.id, command.direction(), base,
                    command.conflict() == null ? Conflict.FAIL : command.conflict(), manifest);
            // 先准备目录并检查已有文件；文件内容由 Tauri 随后逐条发送/读取。
            for (ItemView item : task.snapshot().items()) {
                op.check();
                String path = RemotePath.join(base, item.relativePath());
                if (command.direction() == Direction.UPLOAD) {
                    if (item.kind() == Kind.DIRECTORY) ensureDirectory(op.channel, session, path);
                    else {
                        Entry present = op.channel.stat(path);
                        if (present != null) {
                            if (task.conflict == Conflict.SKIP && present.kind() == Kind.FILE) {
                                task.finish(item.itemId(), ItemStatus.SKIPPED, null, "目标文件已存在");
                            } else throw error("SFTP_CONFLICT", "目标已存在：" + item.relativePath());
                        }
                    }
                }
                if (item.kind() == Kind.DIRECTORY) task.finish(item.itemId(),
                        command.direction() == Direction.UPLOAD ? ItemStatus.COMPLETED : ItemStatus.SENT, null, null);
            }
            synchronized (registryLock) {
                op.check();
                checkTaskCapacity(session.id);
                if (tasks.values().stream().mapToLong(TransferTaskEntity::itemCount).sum() + task.itemCount() > policy.maxTrackedItems()) {
                    throw error("SFTP_BUSY", "传输清单总容量已满，请稍后重试");
                }
                tasks.put(task.id, task);
            }
            return task.snapshot();
        }
    }

    private void scan(Operation op, String path, String relative, List<ManifestItem> result, int depth) {
        op.check();
        if (depth > policy.maxDepth() || result.size() >= policy.maxFiles()) throw error("SFTP_LIMIT", "目录深度或条目数量超过限制");
        Entry entry = op.channel.stat(path);
        if (entry == null) throw error("SFTP_NOT_FOUND", "扫描期间文件已被移除");
        if (entry.kind() != Kind.FILE && entry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "目录包含符号链接或特殊文件，请单独选择普通文件/目录");
        result.add(new ManifestItem(relative, entry.kind(), entry.kind() == Kind.FILE ? entry.size() : 0));
        if (entry.kind() == Kind.DIRECTORY) {
            for (Entry child : op.channel.list(path, policy.maxFiles() - result.size())) {
                scan(op, child.path(), relative + "/" + child.name(), result, depth + 1);
            }
        }
    }

    /** 补全父目录；拒绝重复路径、文件/目录碰撞和恶意相对路径。 */
    private List<ManifestItem> normalizeManifest(List<ManifestItem> input) {
        if (input == null || input.isEmpty() || input.size() > policy.maxFiles()) throw error("SFTP_LIMIT", "文件清单为空或超过条目限制");
        Map<String, ManifestItem> normalized = new LinkedHashMap<>();
        long bytes = 0;
        for (ManifestItem item : input) {
            if (item == null) throw error("SFTP_INVALID", "清单条目不能为空");
            String relative = RemotePath.relative(item.relativePath(), policy.maxDepth());
            if (item.kind() != Kind.FILE && item.kind() != Kind.DIRECTORY || item.size() < 0
                    || item.kind() == Kind.DIRECTORY && item.size() != 0) throw error("SFTP_INVALID", "文件类型或大小不合法");
            if (item.size() > policy.maxFileSize() || item.size() > policy.maxTaskBytes() - bytes) throw error("SFTP_LIMIT", "文件或任务字节数超过限制");
            bytes += item.size();
            if (normalized.putIfAbsent(relative, item) != null) throw error("SFTP_INVALID", "清单存在重复路径");
        }
        for (ManifestItem item : List.copyOf(normalized.values())) {
            String relative = item.relativePath();
            int slash;
            while ((slash = relative.lastIndexOf('/')) >= 0) {
                relative = relative.substring(0, slash);
                ManifestItem parent = normalized.putIfAbsent(relative, new ManifestItem(relative, Kind.DIRECTORY, 0));
                if (parent != null && parent.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "文件路径同时被用作目录");
                if (normalized.size() > policy.maxFiles()) throw error("SFTP_LIMIT", "补全目录后的条目数量超过限制");
            }
        }
        return normalized.values().stream().sorted(Comparator.comparingInt((ManifestItem i) -> i.relativePath().split("/").length)
                .thenComparing(i -> i.kind() != Kind.DIRECTORY).thenComparing(ManifestItem::relativePath)).toList();
    }

    public TransferView task(String owner, String id) { return requireTask(owner, id).snapshot(); }
    /** 文件响应头只需一个条目，避免每下载一个文件都复制整个目录清单。 */
    public ItemView downloadItem(String owner, String id, String itemId) {
        TransferTaskEntity task = requireTask(owner, id);
        ItemView item = task.item(itemId);
        if (task.direction != Direction.DOWNLOAD || item.kind() != Kind.FILE) throw error("SFTP_INVALID", "条目不可下载");
        return item;
    }
    public List<TransferView> tasks(String owner, String sessionId) {
        requireSession(owner, sessionId);
        return tasks.values().stream().filter(t -> t.sessionId.equals(sessionId)).map(TransferTaskEntity::snapshot).toList();
    }
    public List<Progress> progress(String owner, String sessionId) {
        requireSession(owner, sessionId); // 查询/SSE 不刷新活跃时间，避免无人操作时永久保活。
        return tasks.values().stream().filter(t -> t.sessionId.equals(sessionId)).map(TransferTaskEntity::progress).toList();
    }
    public void confirm(String owner, String id, String itemId, boolean saved) { requireTask(owner, id).confirm(itemId, saved); }
    public void cancel(String owner, String id) {
        TransferTaskEntity task = requireTask(owner, id);
        task.cancel();
        operations.stream().filter(op -> op.task == task).forEach(Operation::abort);
    }

    public void upload(String owner, String id, String itemId, InputStream input, long contentLength) {
        TransferTaskEntity task = requireTask(owner, id);
        SftpSessionEntity session = requireSession(owner, task.sessionId);
        ItemView before = task.item(itemId);
        if (contentLength >= 0 && contentLength != before.size()) throw error("SFTP_INVALID", "请求长度与清单不一致");
        try (Operation op = operation(session, task)) {
            ItemView item = task.begin(itemId, Direction.UPLOAD);
            String target = RemotePath.join(task.remotePath, item.relativePath());
            String temporary = RemotePath.parent(target) + "/.sftp-" + task.id + "-" + itemId + ".part";
            boolean committed = false;
            boolean temporaryOwned = false;
            try {
                op.requestStream = input;
                checkParent(op.channel, session, target);
                if (op.channel.stat(target) != null) throw error("SFTP_CONFLICT", "上传期间目标文件已存在，请重新创建任务");
                if (op.channel.stat(temporary) != null) throw error("SFTP_CONFLICT", "临时文件已存在，请创建新任务");
                ExactInputStream counted = new ExactInputStream(input, item.size(), op);
                temporaryOwned = true;
                op.channel.upload(temporary, counted, delta -> { op.touch(); task.progress(itemId, delta); }, () -> op.cancelled.get() || task.cancelled());
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "实际上传长度与清单不一致");
                Entry stored = op.channel.stat(temporary);
                if (stored == null || stored.size() != item.size()) throw error("SFTP_SIZE_MISMATCH", "远程文件长度不一致");
                checkParent(op.channel, session, target);
                if (op.channel.stat(target) != null) throw error("SFTP_CONFLICT", "目标文件已存在，未覆盖");
                op.channel.rename(temporary, target);
                committed = true;
                task.finish(itemId, ItemStatus.COMPLETED, null, null);
            } catch (Exception e) {
                fail(task, itemId, e);
                throw asBusiness(e);
            } finally {
                op.requestStream = null;
                if (!committed && temporaryOwned) {
                    try { op.channel.remove(temporary); }
                    catch (RuntimeException cleanup) {
                        // 连接失效时不能保证补偿成功，记录确定的临时路径，供管理员清理。
                        log.warn("SFTP 临时文件清理未确认 transferId={} path={}", task.id, temporary);
                    }
                }
            }
        }
    }

    public void download(String owner, String id, String itemId, OutputStream output) {
        TransferTaskEntity task = requireTask(owner, id);
        SftpSessionEntity session = requireSession(owner, task.sessionId);
        try (Operation op = operation(session, task)) {
            ItemView item = task.begin(itemId, Direction.DOWNLOAD);
            try {
                op.requestStream = output;
                String path = existing(op.channel, session, RemotePath.join(task.remotePath, item.relativePath()));
                Entry entry = op.channel.stat(path);
                if (entry == null || entry.kind() != Kind.FILE || entry.size() != item.size()) throw error("SFTP_SOURCE_CHANGED", "文件在创建任务后发生变化，请重新创建任务");
                ExactOutputStream counted = new ExactOutputStream(output, item.size(), op);
                op.channel.download(path, counted, delta -> { op.touch(); task.progress(itemId, delta); }, () -> op.cancelled.get() || task.cancelled());
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "下载长度与清单不一致");
                output.flush();
                task.finish(itemId, ItemStatus.SENT, null, null);
            } catch (Exception e) { fail(task, itemId, e); throw asBusiness(e); }
            finally { op.requestStream = null; }
        }
    }

    private String existing(ISftpClientPort.Channel channel, SftpSessionEntity session, String path) {
        String requested = RemotePath.within(session.rootPath, path);
        checkParent(channel, session, requested);
        Entry entry = channel.stat(requested);
        if (entry == null) throw error("SFTP_NOT_FOUND", "服务器路径不存在");
        if (entry.kind() == Kind.SYMLINK) throw error("SFTP_PATH_FORBIDDEN", "首版不跟随符号链接");
        return RemotePath.within(session.rootPath, channel.realpath(requested));
    }
    private void checkParent(ISftpClientPort.Channel channel, SftpSessionEntity session, String target) {
        RemotePath.within(session.rootPath, target);
        if (target.equals(session.rootPath)) return;
        String parent = RemotePath.parent(target);
        String real = RemotePath.within(session.rootPath, channel.realpath(parent));
        if (!real.equals(parent)) throw error("SFTP_PATH_FORBIDDEN", "路径包含符号链接，请使用真实目录");
    }
    private void ensureDirectory(ISftpClientPort.Channel channel, SftpSessionEntity session, String path) {
        String target = RemotePath.within(session.rootPath, path);
        if (target.equals(session.rootPath)) return;
        checkParent(channel, session, target);
        Entry entry = channel.stat(target);
        if (entry == null) channel.mkdir(target);
        else if (entry.kind() != Kind.DIRECTORY) throw error("SFTP_CONFLICT", "目标路径已存在且不是目录");
    }

    private SftpSessionEntity requireSession(String owner, String id) {
        SftpSessionEntity session = id == null ? null : sessions.get(id);
        if (session == null || session.closed || !session.ownerId.equals(owner)) throw error("SFTP_NOT_FOUND", "文件管理会话不存在或无权访问");
        return session;
    }
    private TransferTaskEntity requireTask(String owner, String id) {
        TransferTaskEntity task = id == null ? null : tasks.get(id);
        if (task == null) throw error("SFTP_NOT_FOUND", "传输任务不存在或已过期");
        requireSession(owner, task.sessionId);
        return task;
    }
    private void checkTaskCapacity(String sessionId) {
        if (tasks.values().stream().filter(t -> t.sessionId.equals(sessionId)).count() >= policy.maxTasksPerSession()) throw error("SFTP_BUSY", "当前窗口任务过多，请等待清理或重新打开窗口");
    }
    private void fail(TransferTaskEntity task, String itemId, Exception e) {
        SftpException failure = asBusiness(e);
        task.finish(itemId, ItemStatus.FAILED, failure.getCode(), failure.getMessage());
        log.warn("SFTP 条目失败 transferId={} itemId={} code={}", task.id, itemId, failure.getCode());
    }
    private static SftpException error(String code, String message) { return new SftpException(code, message); }
    private static SftpException asBusiness(Exception e) {
        return e instanceof SftpException business ? business : new SftpException("SFTP_IO_ERROR", "文件流传输失败", e);
    }

    /** 活动操作名额覆盖网络连接和传输全过程；拒绝排队，避免请求积压。 */
    private Operation operation(SftpSessionEntity session, TransferTaskEntity task) {
        Operation op = new Operation(session, task);
        synchronized (registryLock) {
            if (session.closed || session.connection == null || !session.connection.connected()) throw error("SFTP_UNAVAILABLE", "SFTP 会话已断开，请重新打开");
            if (task != null && tasks.get(task.id) != task) throw error("SFTP_NOT_FOUND", "传输任务已过期");
            long sameSession = operations.stream().filter(o -> o.session == session && (o.task != null) == (task != null)).count();
            if (task == null ? sameSession >= 1 : sameSession >= policy.maxTransfersPerSession()
                    || operations.stream().filter(o -> o.task != null).count() >= policy.maxTransfers()
                    || operations.stream().filter(o -> o.task != null && o.session.ownerId.equals(session.ownerId)).count() >= policy.maxTransfersPerUser()) {
                throw error("SFTP_BUSY", "文件操作并发已满，请稍后重试");
            }
            operations.add(op);
            session.lastActiveAt = System.currentTimeMillis();
        }
        try { op.channel = session.connection.channel(); op.check(); return op; }
        catch (RuntimeException e) { op.close(); throw e; }
    }

    private final class Operation implements AutoCloseable {
        final SftpSessionEntity session;
        final TransferTaskEntity task;
        final Thread thread = Thread.currentThread();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastProgress = System.currentTimeMillis();
        volatile ISftpClientPort.Channel channel;
        volatile Closeable requestStream;
        Operation(SftpSessionEntity session, TransferTaskEntity task) { this.session = session; this.task = task; }
        void touch() { lastProgress = System.currentTimeMillis(); session.lastActiveAt = lastProgress; }
        void check() {
            if (cancelled.get() || session.closed || task != null && task.cancelled() || Thread.currentThread().isInterrupted()) throw error("SFTP_CANCELLED", "文件操作已取消或超时");
        }
        void abort() {
            synchronized (this) {
                // 不允许在操作返回之后中断已被容器复用的线程。
                if (closed.get() || !cancelled.compareAndSet(false, true)) return;
                thread.interrupt();
                if (channel != null) channel.close();
                Closeable stream = requestStream;
                // 关闭只持有当前操作的锁；不能持有注册表锁等待 Servlet 流释放。
                if (stream != null) { try { stream.close(); } catch (IOException ignored) { } }
            }
        }
        @Override public void close() {
            synchronized (this) { if (!closed.compareAndSet(false, true)) return; }
            try { if (channel != null) channel.close(); }
            finally { operations.remove(this); session.lastActiveAt = System.currentTimeMillis(); }
        }
    }

    private void closeInternal(SftpSessionEntity session) {
        closeInternal(session, false, 0);
    }
    private void closeInternal(SftpSessionEntity session, boolean idleOnly, long now) {
        synchronized (registryLock) {
            if (session.closed) return;
            // 与新操作登记共用锁，防止刚恢复使用的窗口被空闲扫描误关。
            if (idleOnly && (operations.stream().anyMatch(op -> op.session == session)
                    || now - session.lastActiveAt < policy.sessionIdleTimeout().toMillis())) return;
            session.closed = true;
        }
        try {
            tasks.values().stream().filter(t -> t.sessionId.equals(session.id)).forEach(TransferTaskEntity::cancel);
            operations.stream().filter(op -> op.session == session).forEach(Operation::abort);
        } finally {
            try { if (session.connection != null) session.connection.close(); }
            finally {
                tasks.values().removeIf(t -> t.sessionId.equals(session.id));
                // 资源释放后才归还会话名额，关闭中的连接也受配额约束。
                sessions.remove(session.id, session);
            }
        }
    }

    @Scheduled(fixedDelayString = "${ai.ssh.sftp.cleanup-interval:10s}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        for (Operation op : operations) {
            long timeout = op.task == null ? policy.operationTimeout().toMillis() : policy.transferIdleTimeout().toMillis();
            if (now - op.lastProgress >= timeout) op.abort();
        }
        for (SftpSessionEntity session : sessions.values()) {
            closeInternal(session, true, now);
        }
        synchronized (registryLock) {
            tasks.values().removeIf(t -> operations.stream().noneMatch(op -> op.task == t)
                    && now - t.progress().updatedAt().toEpochMilli() >= policy.taskRetention().toMillis());
        }
    }
    @PreDestroy public void shutdown() { List.copyOf(sessions.values()).forEach(this::closeInternal); }

    /** 最多读取声明长度加一个字节，拒绝短读和超长内容，避免临时文件无限增长。 */
    private static final class ExactInputStream extends FilterInputStream {
        long count; final long expected; final Operation operation;
        ExactInputStream(InputStream input, long expected, Operation operation) { super(input); this.expected = expected; this.operation = operation; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            operation.check();
            if (len == 0) return 0;
            int n = in.read(b, off, (int) Math.min(len, expected - count + 1));
            if (n > 0) { count += n; operation.touch(); if (count > expected) throw error("SFTP_SIZE_MISMATCH", "上传内容超过声明大小"); }
            return n;
        }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public void close() { /* Servlet 请求流由入口管理。 */ }
    }
    private static final class ExactOutputStream extends FilterOutputStream {
        long count; final long expected; final Operation operation;
        ExactOutputStream(OutputStream output, long expected, Operation operation) { super(output); this.expected = expected; this.operation = operation; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            operation.check();
            if (len > expected - count) throw error("SFTP_SOURCE_CHANGED", "下载期间源文件增大");
            out.write(b, off, len); count += len; operation.touch();
        }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
        @Override public void close() throws IOException { flush(); }
    }
}
