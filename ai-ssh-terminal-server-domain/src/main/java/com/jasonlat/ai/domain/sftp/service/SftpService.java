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

    public SftpService(ISftpClientPort client, SftpPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    public SessionView open(String owner, SshConnectionEntity credentials, SshConnectionConfigEntity config, String root) {
        SftpSessionEntity session = new SftpSessionEntity(owner, credentials.getConnectionId());
        log.info(
                "SFTP 会话开始连接 userId={} sessionId={} connectionId={} requestedRoot={}",
                owner,
                session.id,
                credentials.getConnectionId(),
                root
        );
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
            log.info(
                    "SFTP 会话连接成功 userId={} sessionId={} connectionId={} rootPath={}",
                    owner,
                    session.id,
                    credentials.getConnectionId(),
                    session.rootPath
            );
            return session.view();
        } catch (RuntimeException e) {
            log.warn(
                    "SFTP 会话连接失败 userId={} sessionId={} connectionId={} errorType={} message={}",
                    owner,
                    session.id,
                    credentials.getConnectionId(),
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
            closeInternal(session);
            if (connection != null) connection.close();
            throw e;
        }
    }

    public SessionView session(String owner, String id) {
        SessionView view = requireSession(owner, id).view();
        log.info("SFTP 查询会话 userId={} sessionId={} rootPath={}", owner, id, view.rootPath());
        return view;
    }

    public void close(String owner, String id) {
        log.info("SFTP 会话收到关闭请求 userId={} sessionId={}", owner, id);
        closeInternal(requireSession(owner, id));
    }

    public List<Entry> list(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始浏览目录 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            String target = existing(op.channel, session, path == null ? session.rootPath : path);
            Entry entry = op.channel.stat(target);
            if (entry == null || entry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "只能浏览目录");
            List<Entry> result = op.channel.list(target, policy.maxFiles());
            log.info("SFTP 浏览目录完成 userId={} sessionId={} path={} entries={}", owner, id, target, result.size());
            return result;
        }
    }
    public void mkdir(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始创建目录 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            String directory = RemotePath.within(session.rootPath, path);
            ensureDirectory(op.channel, session, directory);
            log.info("SFTP 创建目录完成 userId={} sessionId={} path={}", owner, id, directory);
        }
    }

    @Override
    public void createFile(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始创建空文件 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            String target = RemotePath.within(session.rootPath, path);
            if (target.equals(session.rootPath)) {
                throw error("SFTP_INVALID", "不能将文件创建为会话根目录");
            }
            checkParent(op.channel, session, target);
            if (op.channel.stat(target) != null) {
                throw error("SFTP_CONFLICT", "目标文件或目录已存在");
            }

            // 先创建同目录临时文件，再改名提交。这样即使连接中断，也不会在用户指定的
            // 最终路径留下一个状态不明确的半成品；目标存在时也绝不覆盖。
            String temporary = RemotePath.join(
                    RemotePath.parent(target),
                    ".agent-ssh-create-" + UUID.randomUUID() + ".part"
            );
            boolean committed = false;
            try {
                op.channel.upload(
                        temporary,
                        InputStream.nullInputStream(),
                        ignored -> { },
                        () -> session.closed
                );
                op.check();
                if (op.channel.stat(target) != null) {
                    throw error("SFTP_CONFLICT", "目标文件或目录已存在");
                }
                op.channel.rename(temporary, target);
                committed = true;
                log.info("SFTP 创建空文件完成 userId={} sessionId={} path={}", owner, id, target);
            } finally {
                if (!committed) {
                    try {
                        if (op.channel.stat(temporary) != null) {
                            op.channel.remove(temporary);
                        }
                    } catch (RuntimeException cleanupFailure) {
                        // 清理失败不覆盖真正的创建失败原因；临时文件名带随机 UUID，
                        // 后续可以由服务器运维策略安全识别和清理。
                        log.warn("SFTP 创建空文件失败后未能清理临时文件 path={}", temporary);
                    }
                }
            }
        }
    }

    @Override
    public void delete(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始删除条目 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            String target = existing(op.channel, session, path);
            if (target.equals(session.rootPath)) {
                throw error("SFTP_PATH_FORBIDDEN", "不能删除文件管理根目录");
            }
            Entry entry = op.channel.stat(target);
            log.info("SFTP 删除目标校验完成 userId={} sessionId={} path={} kind={}", owner, id, target, entry.kind());
            if (entry.kind() == Kind.FILE) {
                op.channel.remove(target);
                log.info("SFTP 删除文件完成 userId={} sessionId={} path={}", owner, id, target);
                return;
            }
            if (entry.kind() == Kind.DIRECTORY) {
                // 只探测一个条目即可判断非空，避免为了删除操作加载整个大目录。
                if (!op.channel.list(target, 1).isEmpty()) {
                    throw error("SFTP_CONFLICT", "目录不为空，仅支持删除空目录");
                }
                op.channel.rmdir(target);
                log.info("SFTP 删除空目录完成 userId={} sessionId={} path={}", owner, id, target);
                return;
            }
            throw error("SFTP_INVALID", "只支持删除普通文件或空目录");
        }
    }

    public TransferView create(String owner, CreateTransfer command) {
        if (command == null || command.direction() == null || command.remotePath() == null) {
            throw error("SFTP_INVALID", "传输参数不完整");
        }
        SftpSessionEntity session = requireSession(owner, command.sftpSessionId());
        log.info(
                "SFTP 开始创建传输任务 userId={} sessionId={} direction={} remotePath={} requestedItems={}",
                owner,
                session.id,
                command.direction(),
                command.remotePath(),
                command.items() == null ? 0 : command.items().size()
        );
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
                long trackedItems = tasks.values().stream()
                        .mapToLong(TransferTaskEntity::itemCount)
                        .sum();
                if (trackedItems + task.itemCount() > policy.maxTrackedItems()) {
                    throw error("SFTP_BUSY", "传输清单总容量已满，请稍后重试");
                }
                tasks.put(task.id, task);
            }
            log.info(
                    "SFTP 传输任务创建完成 userId={} sessionId={} transferId={} direction={} items={}",
                    owner,
                    session.id,
                    task.id,
                    task.direction,
                    task.itemCount()
            );
            return task.snapshot();
        }
    }

    private void scan(Operation op, String path, String relative, List<ManifestItem> result, int depth) {
        op.check();
        if (depth > policy.maxDepth() || result.size() >= policy.maxFiles()) throw error("SFTP_LIMIT", "目录深度或条目数量超过限制");
        Entry entry = op.channel.stat(path);
        if (entry == null) throw error("SFTP_NOT_FOUND", "扫描期间文件已被移除");
        if (entry.kind() != Kind.FILE && entry.kind() != Kind.DIRECTORY) {
            throw error(
                    "SFTP_INVALID",
                    "目录包含符号链接或特殊文件，请单独选择普通文件/目录"
            );
        }
        result.add(new ManifestItem(relative, entry.kind(), entry.kind() == Kind.FILE ? entry.size() : 0));
        if (entry.kind() == Kind.DIRECTORY) {
            for (Entry child : op.channel.list(path, policy.maxFiles() - result.size())) {
                scan(op, child.path(), relative + "/" + child.name(), result, depth + 1);
            }
        }
    }

    /** 补全父目录；拒绝重复路径、文件/目录碰撞和恶意相对路径。 */
    private List<ManifestItem> normalizeManifest(List<ManifestItem> input) {
        if (input == null || input.isEmpty() || input.size() > policy.maxFiles()) {
            throw error("SFTP_LIMIT", "文件清单为空或超过条目限制");
        }
        Map<String, ManifestItem> normalized = new LinkedHashMap<>();
        long bytes = 0;
        for (ManifestItem item : input) {
            if (item == null) throw error("SFTP_INVALID", "清单条目不能为空");
            String relative = RemotePath.relative(item.relativePath(), policy.maxDepth());
            if (item.kind() != Kind.FILE && item.kind() != Kind.DIRECTORY || item.size() < 0
                    || item.kind() == Kind.DIRECTORY && item.size() != 0) throw error("SFTP_INVALID", "文件类型或大小不合法");
            if (item.size() > policy.maxFileSize()
                    || item.size() > policy.maxTaskBytes() - bytes) {
                throw error("SFTP_LIMIT", "文件或任务字节数超过限制");
            }
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
        return normalized.values().stream()
                .sorted(Comparator
                        .comparingInt((ManifestItem item) -> item.relativePath().split("/").length)
                        .thenComparing(item -> item.kind() != Kind.DIRECTORY)
                        .thenComparing(ManifestItem::relativePath))
                .toList();
    }

    public TransferView task(String owner, String id) {
        TransferView view = requireTask(owner, id).snapshot();
        log.info(
                "SFTP 查询传输任务 userId={} transferId={} sessionId={} status={}",
                owner,
                id,
                view.sftpSessionId(),
                view.progress().status()
        );
        return view;
    }
    /** 文件响应头只需一个条目，避免每下载一个文件都复制整个目录清单。 */
    public ItemView downloadItem(String owner, String id, String itemId) {
        TransferTaskEntity task = requireTask(owner, id);
        ItemView item = task.item(itemId);
        if (task.direction != Direction.DOWNLOAD || item.kind() != Kind.FILE) throw error("SFTP_INVALID", "条目不可下载");
        return item;
    }
    public List<TransferView> tasks(String owner, String sessionId) {
        requireSession(owner, sessionId);
        List<TransferView> result = tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .map(TransferTaskEntity::snapshot)
                .toList();
        log.info("SFTP 查询传输任务列表 userId={} sessionId={} tasks={}", owner, sessionId, result.size());
        return result;
    }
    public List<Progress> progress(String owner, String sessionId) {
        requireSession(owner, sessionId); // 查询/SSE 不刷新活跃时间，避免无人操作时永久保活。
        return tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .map(TransferTaskEntity::progress)
                .toList();
    }

    public void confirm(String owner, String id, String itemId, boolean saved) {
        TransferTaskEntity task = requireTask(owner, id);
        task.confirm(itemId, saved);
        log.info("SFTP 下载保存确认 userId={} transferId={} itemId={} saved={}", owner, id, itemId, saved);
    }
    public void cancel(String owner, String id) {
        TransferTaskEntity task = requireTask(owner, id);
        log.info("SFTP 传输任务收到取消请求 userId={} transferId={} sessionId={}", owner, id, task.sessionId);
        task.cancel();
        operations.stream().filter(op -> op.task == task).forEach(Operation::abort);
        log.info("SFTP 传输任务已标记取消 userId={} transferId={} sessionId={}", owner, id, task.sessionId);
    }

    public void upload(String owner, String id, String itemId, InputStream input, long contentLength) {
        TransferTaskEntity task = requireTask(owner, id);
        SftpSessionEntity session = requireSession(owner, task.sessionId);
        ItemView before = task.item(itemId);
        log.info(
                "SFTP 文件上传开始 userId={} sessionId={} transferId={} itemId={} relativePath={} expectedBytes={} contentLength={}",
                owner,
                session.id,
                task.id,
                itemId,
                before.relativePath(),
                before.size(),
                contentLength
        );
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
                op.channel.upload(
                        temporary,
                        counted,
                        delta -> {
                            op.touch();
                            task.progress(itemId, delta);
                        },
                        () -> op.cancelled.get() || task.cancelled()
                );
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "实际上传长度与清单不一致");
                Entry stored = op.channel.stat(temporary);
                if (stored == null || stored.size() != item.size()) throw error("SFTP_SIZE_MISMATCH", "远程文件长度不一致");
                checkParent(op.channel, session, target);
                if (op.channel.stat(target) != null) throw error("SFTP_CONFLICT", "目标文件已存在，未覆盖");
                op.channel.rename(temporary, target);
                committed = true;
                task.finish(itemId, ItemStatus.COMPLETED, null, null);
                log.info(
                        "SFTP 文件上传完成 userId={} sessionId={} transferId={} itemId={} path={} bytes={}",
                        owner,
                        session.id,
                        task.id,
                        itemId,
                        target,
                        item.size()
                );
            } catch (Exception e) {
                fail(task, itemId, e);
                throw asBusiness(e);
            } finally {
                op.requestStream = null;
                if (!committed && temporaryOwned) {
                    try {
                        op.channel.remove(temporary);
                    } catch (RuntimeException cleanup) {
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
        ItemView requestedItem = task.item(itemId);
        log.info(
                "SFTP 文件下载开始 userId={} sessionId={} transferId={} itemId={} relativePath={} expectedBytes={}",
                owner,
                session.id,
                task.id,
                itemId,
                requestedItem.relativePath(),
                requestedItem.size()
        );
        try (Operation op = operation(session, task)) {
            ItemView item = task.begin(itemId, Direction.DOWNLOAD);
            try {
                op.requestStream = output;
                String path = existing(op.channel, session, RemotePath.join(task.remotePath, item.relativePath()));
                Entry entry = op.channel.stat(path);
                if (entry == null || entry.kind() != Kind.FILE || entry.size() != item.size()) {
                    throw error(
                            "SFTP_SOURCE_CHANGED",
                            "文件在创建任务后发生变化，请重新创建任务"
                    );
                }
                ExactOutputStream counted = new ExactOutputStream(output, item.size(), op);
                op.channel.download(
                        path,
                        counted,
                        delta -> {
                            op.touch();
                            task.progress(itemId, delta);
                        },
                        () -> op.cancelled.get() || task.cancelled()
                );
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "下载长度与清单不一致");
                output.flush();
                task.finish(itemId, ItemStatus.SENT, null, null);
                log.info(
                        "SFTP 文件下载发送完成 userId={} sessionId={} transferId={} itemId={} path={} bytes={}",
                        owner,
                        session.id,
                        task.id,
                        itemId,
                        path,
                        item.size()
                );
            } catch (Exception exception) {
                fail(task, itemId, exception);
                throw asBusiness(exception);
            } finally {
                op.requestStream = null;
            }
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
        if (session == null || session.closed || !session.ownerId.equals(owner)) {
            log.warn(
                    "SFTP 会话校验失败 userId={} sessionId={} found={} closed={} actualOwner={}",
                    owner,
                    id,
                    session != null,
                    session != null && session.closed,
                    session == null ? null : session.ownerId
            );
            throw error("SFTP_NOT_FOUND", "文件管理会话不存在或无权访问");
        }
        return session;
    }
    private TransferTaskEntity requireTask(String owner, String id) {
        TransferTaskEntity task = id == null ? null : tasks.get(id);
        if (task == null) {
            log.warn("SFTP 任务校验失败 userId={} transferId={} found=false", owner, id);
            throw error("SFTP_NOT_FOUND", "传输任务不存在或已过期");
        }
        requireSession(owner, task.sessionId);
        return task;
    }
    private void checkTaskCapacity(String sessionId) {
        long sessionTasks = tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .count();
        if (sessionTasks >= policy.maxTasksPerSession()) {
            log.warn(
                    "SFTP 会话任务容量已满 sessionId={} currentTasks={} limit={}",
                    sessionId,
                    sessionTasks,
                    policy.maxTasksPerSession()
            );
            throw error("SFTP_BUSY", "当前窗口任务过多，请等待清理或重新打开窗口");
        }
    }
    private void fail(TransferTaskEntity task, String itemId, Exception e) {
        SftpException failure = asBusiness(e);
        task.finish(itemId, ItemStatus.FAILED, failure.getCode(), failure.getMessage());
        SftpSessionEntity session = sessions.get(task.sessionId);
        log.warn(
                "SFTP 传输条目失败 userId={} sessionId={} transferId={} itemId={} code={} message={}",
                session == null ? null : session.ownerId,
                task.sessionId,
                task.id,
                itemId,
                failure.getCode(),
                failure.getMessage()
        );
    }
    private static SftpException error(String code, String message) {
        return new SftpException(code, message);
    }
    private static SftpException asBusiness(Exception e) {
        return e instanceof SftpException business ? business : new SftpException("SFTP_IO_ERROR", "文件流传输失败", e);
    }

    /** 活动操作名额覆盖网络连接和传输全过程；拒绝排队，避免请求积压。 */
    private Operation operation(SftpSessionEntity session, TransferTaskEntity task) {
        Operation op = new Operation(session, task);
        synchronized (registryLock) {
            if (session.closed
                    || session.connection == null
                    || !session.connection.connected()) {
                throw error("SFTP_UNAVAILABLE", "SFTP 会话已断开，请重新打开");
            }
            if (task != null && tasks.get(task.id) != task) {
                throw error("SFTP_NOT_FOUND", "传输任务已过期");
            }

            long sameSession = operations.stream()
                    .filter(operation -> operation.session == session
                            && (operation.task != null) == (task != null))
                    .count();

            long totalTransfers = operations.stream()
                    .filter(operation -> operation.task != null)
                    .count();
            long ownerTransfers = operations.stream()
                    .filter(operation -> operation.task != null
                            && operation.session.ownerId.equals(session.ownerId))
                    .count();

            boolean directoryOperationBusy = task == null && sameSession >= 1;
            boolean transferCapacityReached = task != null
                    && (sameSession >= policy.maxTransfersPerSession()
                    || totalTransfers >= policy.maxTransfers()
                    || ownerTransfers >= policy.maxTransfersPerUser());
            if (directoryOperationBusy || transferCapacityReached) {
                log.warn(
                        "SFTP 操作并发被拒绝 userId={} sessionId={} transferId={} sameSession={} totalTransfers={} ownerTransfers={}",
                        session.ownerId,
                        session.id,
                        task == null ? null : task.id,
                        sameSession,
                        totalTransfers,
                        ownerTransfers
                );
                throw error("SFTP_BUSY", "文件操作并发已满，请稍后重试");
            }
            operations.add(op);
            session.lastActiveAt = System.currentTimeMillis();
        }

        try {
            op.channel = session.connection.channel();
            op.check();
            return op;
        } catch (RuntimeException exception) {
            op.close();
            throw exception;
        }
    }

    /**
     * 一次目录操作或文件传输的运行上下文。
     *
     * <p>该对象统一持有 Channel、请求流、取消标记和最后活动时间。清理线程调用
     * {@link #abort()} 时会关闭 Channel 与请求流，使阻塞中的网络调用尽快返回。</p>
     */
    private final class Operation implements AutoCloseable {
        final SftpSessionEntity session;
        final TransferTaskEntity task;
        final Thread thread = Thread.currentThread();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastProgress = System.currentTimeMillis();
        volatile ISftpClientPort.Channel channel;
        volatile Closeable requestStream;
        Operation(SftpSessionEntity session, TransferTaskEntity task) {
            this.session = session;
            this.task = task;
        }

        void touch() {
            lastProgress = System.currentTimeMillis();
            session.lastActiveAt = lastProgress;
        }

        void check() {
            boolean taskCancelled = task != null && task.cancelled();
            if (cancelled.get()
                    || session.closed
                    || taskCancelled
                    || Thread.currentThread().isInterrupted()) {
                throw error("SFTP_CANCELLED", "文件操作已取消或超时");
            }
        }

        void abort() {
            synchronized (this) {
                // 不允许在操作返回之后中断已被容器复用的线程。
                if (closed.get() || !cancelled.compareAndSet(false, true)) {
                    return;
                }
                thread.interrupt();
                if (channel != null) {
                    channel.close();
                }
                Closeable stream = requestStream;
                // 关闭只持有当前操作的锁；不能持有注册表锁等待 Servlet 流释放。
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // 中止阶段无法再向客户端可靠返回该异常，原操作会收到取消状态。
                    }
                }
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
            }

            try {
                if (channel != null) {
                    channel.close();
                }
            } finally {
                operations.remove(this);
                session.lastActiveAt = System.currentTimeMillis();
            }
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
        log.info(
                "SFTP 会话开始释放 userId={} sessionId={} connectionId={} reason={}",
                session.ownerId,
                session.id,
                session.connectionId,
                idleOnly ? "IDLE_TIMEOUT" : "CLOSE"
        );
        try {
            tasks.values().stream().filter(t -> t.sessionId.equals(session.id)).forEach(TransferTaskEntity::cancel);
            operations.stream().filter(op -> op.session == session).forEach(Operation::abort);
        } finally {
            try {
                if (session.connection != null) {
                    session.connection.close();
                }
            } finally {
                tasks.values().removeIf(t -> t.sessionId.equals(session.id));
                // 资源释放后才归还会话名额，关闭中的连接也受配额约束。
                sessions.remove(session.id, session);
                log.info(
                        "SFTP 会话释放完成 userId={} sessionId={} connectionId={}",
                        session.ownerId,
                        session.id,
                        session.connectionId
                );
            }
        }
    }

    @Scheduled(fixedDelayString = "${ai.ssh.sftp.cleanup-interval:10s}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        for (Operation op : operations) {
            long timeout = op.task == null ? policy.operationTimeout().toMillis() : policy.transferIdleTimeout().toMillis();
            if (now - op.lastProgress >= timeout) {
                log.warn(
                        "SFTP 操作超时，准备中止 userId={} sessionId={} transferId={} idleMillis={} timeoutMillis={}",
                        op.session.ownerId,
                        op.session.id,
                        op.task == null ? null : op.task.id,
                        now - op.lastProgress,
                        timeout
                );
                op.abort();
            }
        }
        for (SftpSessionEntity session : sessions.values()) {
            closeInternal(session, true, now);
        }
        synchronized (registryLock) {
            tasks.values().removeIf(t -> operations.stream().noneMatch(op -> op.task == t)
                    && now - t.progress().updatedAt().toEpochMilli() >= policy.taskRetention().toMillis());
        }
    }
    @PreDestroy
    public void shutdown() {
        List.copyOf(sessions.values()).forEach(this::closeInternal);
    }

    /** 最多读取声明长度加一个字节，拒绝短读和超长内容，避免临时文件无限增长。 */
    private static final class ExactInputStream extends FilterInputStream {
        long count;
        final long expected;
        final Operation operation;

        ExactInputStream(InputStream input, long expected, Operation operation) {
            super(input);
            this.expected = expected;
            this.operation = operation;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            operation.check();
            if (length == 0) {
                return 0;
            }

            int read = in.read(
                    buffer,
                    offset,
                    (int) Math.min(length, expected - count + 1)
            );
            if (read > 0) {
                count += read;
                operation.touch();
                if (count > expected) {
                    throw error("SFTP_SIZE_MISMATCH", "上传内容超过声明大小");
                }
            }
            return read;
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            return read(oneByte, 0, 1) < 0 ? -1 : oneByte[0] & 255;
        }

        @Override
        public void close() {
            // Servlet 请求流由 HTTP 入口管理，包装流不得提前关闭它。
        }
    }

    /** 限制下载写入量，并在每次写入后刷新操作活跃时间。 */
    private static final class ExactOutputStream extends FilterOutputStream {
        long count;
        final long expected;
        final Operation operation;

        ExactOutputStream(OutputStream output, long expected, Operation operation) {
            super(output);
            this.expected = expected;
            this.operation = operation;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            operation.check();
            if (length > expected - count) {
                throw error("SFTP_SOURCE_CHANGED", "下载期间源文件增大");
            }
            out.write(buffer, offset, length);
            count += length;
            operation.touch();
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
