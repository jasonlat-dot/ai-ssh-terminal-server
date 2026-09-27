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

    /** 注入协议端口和全部容量、大小、超时策略；领域层不直接依赖 JSch。 */
    public SftpService(ISftpClientPort client, SftpPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    /**
     * 建立独立文件管理连接并确定真实根目录。
     *
     * <p>先占用会话配额再执行网络连接，防止并发请求绕过上限；建连或根目录校验失败时
     * 会统一回收已登记的会话和底层连接。</p>
     */
    public SessionView open(String owner, SshConnectionEntity credentials, SshConnectionConfigEntity config, String root) {
        // 先生成内部 sftpSessionId；它只标识本次文件管理窗口，不等同于保存的 connectionId。
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
            // 通过领域端口建连，具体 JSch 配置只存在于基础设施层。
            connection = client.connect(credentials, config);
            synchronized (registryLock) {
                // 建连期间用户可能已经关闭窗口，因此挂载连接前必须再次检查会话状态。
                if (session.closed) throw error("SFTP_CANCELLED", "会话已关闭");
                session.connection = connection;
            }
            try (Operation operation = operation(session, null)) {
                // "." 由服务器解析为 SSH 账号默认目录；显式 root 则先规范化为绝对路径。
                String realRoot = operation.channel.realpath(root == null || root.isBlank() ? "." : RemotePath.absolute(root));
                // 根路径必须真实存在且为目录，后续所有路径都以它作为安全边界。
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

    /** 返回会话快照；requireSession 同时执行 owner 归属校验。 */
    public SessionView session(String owner, String id) {
        SessionView view = requireSession(owner, id).view();
        log.info("SFTP 查询会话 userId={} sessionId={} rootPath={}", owner, id, view.rootPath());
        return view;
    }

    /** 主动关闭会话，并中止该会话仍在执行的目录操作和传输。 */
    public void close(String owner, String id) {
        log.info("SFTP 会话收到关闭请求 userId={} sessionId={}", owner, id);
        closeInternal(requireSession(owner, id));
    }

    /** 规范化并校验目录路径后，通过一个短生命周期 SFTP Channel 枚举条目。 */
    public List<Entry> list(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始浏览目录 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            // existing 会执行 rootPath 边界、父目录 realpath 和最终条目存在性检查。
            String target = existing(op.channel, session, path == null ? session.rootPath : path);
            Entry entry = op.channel.stat(target);
            if (entry == null || entry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "只能浏览目录");
            List<Entry> result = op.channel.list(target, policy.maxFiles());
            log.info("SFTP 浏览目录完成 userId={} sessionId={} path={} entries={}", owner, id, target, result.size());
            return result;
        }
    }
    /** 创建单级目录；已存在的目录视为幂等成功，已存在的文件视为冲突。 */
    public void mkdir(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始创建目录 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            // within 只做纯路径规范化；ensureDirectory 还会检查真实父目录是否含符号链接。
            String directory = RemotePath.within(session.rootPath, path);
            ensureDirectory(op.channel, session, directory);
            log.info("SFTP 创建目录完成 userId={} sessionId={} path={}", owner, id, directory);
        }
    }

    /** 使用“临时空文件 + rename”创建文件，避免失败时留下目标路径半成品。 */
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

    /** 根据 lstat 类型选择 rm 或 rmdir，只允许删除普通文件和空目录。 */
    @Override
    public void delete(String owner, String id, String path) {
        SftpSessionEntity session = requireSession(owner, id);
        log.info("SFTP 开始删除条目 userId={} sessionId={} requestedPath={}", owner, id, path);
        try (Operation op = operation(session, null)) {
            // 删除前先把用户路径解析成位于 rootPath 内的真实已存在路径。
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

    /**
     * 创建传输任务清单。
     *
     * <p>上传使用客户端提交并经服务端规范化的清单；下载由服务端递归扫描远端，
     * 从而在真正传输前固定文件数量、类型和预期字节数。</p>
     */
    public TransferView create(String owner, CreateTransfer command) {
        // 应用层通常已经验证 DTO，这里仍保留领域边界校验，防止其他调用方绕过 HTTP 入口。
        if (command == null || command.direction() == null || command.remotePath() == null) {
            throw error("SFTP_INVALID", "传输参数不完整");
        }
        SftpSessionEntity session = requireSession(owner, command.sftpSessionId());
        log.info(
                "SFTP 开始创建传输任务 userId={} sessionId={} direction={} conflict={} remotePath={} requestedItems={}",
                owner,
                session.id,
                command.direction(),
                command.conflict(),
                command.remotePath(),
                command.items() == null ? 0 : command.items().size()
        );
        try (Operation op = operation(session, null)) {
            // 第一次容量检查尽早拒绝请求，避免完成远端扫描后才发现任务表已满。
            checkTaskCapacity(session.id);
            // 上传要求选择目标目录；下载允许选择具体文件或目录，但都必须已存在。
            String selected = existing(op.channel, session, command.remotePath());
            Entry selectedEntry = op.channel.stat(selected);
            if (selectedEntry == null) throw error("SFTP_NOT_FOUND", "目标在创建任务期间被移除");
            List<ManifestItem> manifest;
            String base;
            if (command.direction() == Direction.UPLOAD) {
                // 上传文件来自客户端本地，后端只能校验客户端提交的相对路径、类型和大小清单。
                if (selectedEntry.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "上传目标必须是目录");
                manifest = normalizeManifest(command.items());
                base = selected;
            } else {
                // 下载内容位于服务器上，必须由服务端自己扫描，不能信任客户端伪造清单。
                if (selected.equals("/")) throw error("SFTP_INVALID", "请选取一个具体目录或文件下载");
                List<ManifestItem> scanned = new ArrayList<>();
                scan(op, selected, RemotePath.name(selected), scanned, 1);
                manifest = normalizeManifest(scanned);
                base = RemotePath.parent(selected);
            }
            // Task 保存固定清单和基准路径；后续每个 content 请求只能处理清单中的 itemId。
            TransferTaskEntity task = new TransferTaskEntity(session.id, command.direction(), base,
                    command.conflict() == null ? Conflict.FAIL : command.conflict(), manifest);
            // 先准备目录并检查已有文件；文件内容由 Tauri 随后逐条发送/读取。
            for (ItemView item : task.snapshot().items()) {
                op.check();
                String path = RemotePath.join(base, item.relativePath());
                if (command.direction() == Direction.UPLOAD) {
                    // 目录可以在建任务阶段立即创建；文件内容要等 Tauri 随后的 PUT 请求。
                    if (item.kind() == Kind.DIRECTORY) ensureDirectory(op.channel, session, path);
                    else {
                        // 文件同名策略在传输前预判一次，减少无意义的本地文件读取和网络上传。
                        Entry present = op.channel.stat(path);
                        if (present != null) {
                            if (present.kind() != Kind.FILE) {
                                throw error("SFTP_CONFLICT", "目标已存在且不是普通文件：" + item.relativePath());
                            }
                            if (task.conflict == Conflict.SKIP) {
                                task.finish(item.itemId(), ItemStatus.SKIPPED, null, "目标文件已存在");
                            } else if (task.conflict != Conflict.REPLACE) {
                                throw error("SFTP_CONFLICT", "目标已存在：" + item.relativePath());
                            }
                        }
                    }
                }
                if (item.kind() == Kind.DIRECTORY) task.finish(item.itemId(),
                        command.direction() == Direction.UPLOAD ? ItemStatus.COMPLETED : ItemStatus.SENT, null, null);
            }
            synchronized (registryLock) {
                // 扫描期间任务表可能发生变化，因此登记前在同一把锁下再次检查容量。
                op.check();
                checkTaskCapacity(session.id);
                long trackedItems = tasks.values().stream()
                        .mapToLong(TransferTaskEntity::itemCount)
                        .sum();
                if (trackedItems + task.itemCount() > policy.maxTrackedItems()) {
                    throw error("SFTP_BUSY", "传输清单总容量已满，请稍后重试");
                }
                // 只有完整清单通过校验后才公开 transferId，避免客户端看到半初始化任务。
                tasks.put(task.id, task);
            }
            log.info(
                    "SFTP 传输任务创建完成 userId={} sessionId={} transferId={} direction={} conflict={} items={}",
                    owner,
                    session.id,
                    task.id,
                    task.direction,
                    task.conflict,
                    task.itemCount()
            );
            return task.snapshot();
        }
    }

    /** 递归扫描下载目标，同时执行深度、条目数以及特殊文件限制。 */
    private void scan(Operation op, String path, String relative, List<ManifestItem> result, int depth) {
        // 每进入一级目录都检查取消/超时，避免大目录扫描无法及时停止。
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
        // 目录不计文件字节；文件大小会成为下载前后的一致性校验依据。
        result.add(new ManifestItem(relative, entry.kind(), entry.kind() == Kind.FILE ? entry.size() : 0));
        if (entry.kind() == Kind.DIRECTORY) {
            for (Entry child : op.channel.list(path, policy.maxFiles() - result.size())) {
                scan(op, child.path(), relative + "/" + child.name(), result, depth + 1);
            }
        }
    }

    /** 补全父目录；拒绝重复路径、文件/目录碰撞和恶意相对路径。 */
    private List<ManifestItem> normalizeManifest(List<ManifestItem> input) {
        // 空清单没有传输意义；条目上限同时约束递归扫描成本和内存任务表大小。
        if (input == null || input.isEmpty() || input.size() > policy.maxFiles()) {
            throw error("SFTP_LIMIT", "文件清单为空或超过条目限制");
        }
        Map<String, ManifestItem> normalized = new LinkedHashMap<>();
        long bytes = 0;
        for (ManifestItem item : input) {
            // relative 会去除重复分隔符，并拒绝绝对路径、.. 越级和超深目录。
            if (item == null) throw error("SFTP_INVALID", "清单条目不能为空");
            String relative = RemotePath.relative(item.relativePath(), policy.maxDepth());
            if (item.kind() != Kind.FILE && item.kind() != Kind.DIRECTORY || item.size() < 0
                    || item.kind() == Kind.DIRECTORY && item.size() != 0) throw error("SFTP_INVALID", "文件类型或大小不合法");
            if (item.size() > policy.maxFileSize()
                    || item.size() > policy.maxTaskBytes() - bytes) {
                throw error("SFTP_LIMIT", "文件或任务字节数超过限制");
            }
            // 采用逐项减法检查，避免累计值溢出后绕过任务总字节限制。
            bytes += item.size();
            if (normalized.putIfAbsent(relative, item) != null) throw error("SFTP_INVALID", "清单存在重复路径");
        }
        for (ManifestItem item : List.copyOf(normalized.values())) {
            // 客户端只选择文件时也自动补齐它的父目录，保证执行顺序和进度条条目完整。
            String relative = item.relativePath();
            int slash;
            while ((slash = relative.lastIndexOf('/')) >= 0) {
                relative = relative.substring(0, slash);
                ManifestItem parent = normalized.putIfAbsent(relative, new ManifestItem(relative, Kind.DIRECTORY, 0));
                if (parent != null && parent.kind() != Kind.DIRECTORY) throw error("SFTP_INVALID", "文件路径同时被用作目录");
                if (normalized.size() > policy.maxFiles()) throw error("SFTP_LIMIT", "补全目录后的条目数量超过限制");
            }
        }
        // 父目录排在子项之前、同层目录排在文件之前，上传时即可按顺序安全创建目录。
        return normalized.values().stream()
                .sorted(Comparator
                        .comparingInt((ManifestItem item) -> item.relativePath().split("/").length)
                        .thenComparing(item -> item.kind() != Kind.DIRECTORY)
                        .thenComparing(ManifestItem::relativePath))
                .toList();
    }

    /** 返回任务不可变快照，避免 HTTP 层接触可变实体。 */
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
    /** 返回会话内仍处于保留期的全部任务快照。 */
    public List<TransferView> tasks(String owner, String sessionId) {
        requireSession(owner, sessionId);
        List<TransferView> result = tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .map(TransferTaskEntity::snapshot)
                .toList();
        log.info("SFTP 查询传输任务列表 userId={} sessionId={} tasks={}", owner, sessionId, result.size());
        return result;
    }
    /** 为 SSE 生成轻量进度快照；该读取不会刷新会话空闲时间。 */
    public List<Progress> progress(String owner, String sessionId) {
        requireSession(owner, sessionId); // 查询/SSE 不刷新活跃时间，避免无人操作时永久保活。
        return tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .map(TransferTaskEntity::progress)
                .toList();
    }

    /** 接收客户端落盘结果，把下载条目从 SENT 推进到最终完成或失败状态。 */
    public void confirm(String owner, String id, String itemId, boolean saved) {
        TransferTaskEntity task = requireTask(owner, id);
        task.confirm(itemId, saved);
        log.info("SFTP 下载保存确认 userId={} transferId={} itemId={} saved={}", owner, id, itemId, saved);
    }
    /** 标记任务取消，并关闭仍在阻塞 I/O 的活动操作以加速退出。 */
    public void cancel(String owner, String id) {
        TransferTaskEntity task = requireTask(owner, id);
        log.info("SFTP 传输任务收到取消请求 userId={} transferId={} sessionId={}", owner, id, task.sessionId);
        task.cancel();
        operations.stream().filter(op -> op.task == task).forEach(Operation::abort);
        log.info("SFTP 传输任务已标记取消 userId={} transferId={} sessionId={}", owner, id, task.sessionId);
    }

    /**
     * 上传单个清单文件并安全提交。
     *
     * <p>数据先写入任务专属 .part 文件并校验长度。REPLACE 模式会先把原文件移到 .bak，
     * 再把新文件 rename 到目标；提交失败时尽力恢复原文件。</p>
     */
    public void upload(String owner, String id, String itemId, InputStream input, long contentLength) {
        // transferId 先定位任务，再通过任务的 sessionId 找到并校验所属文件管理会话。
        TransferTaskEntity task = requireTask(owner, id);
        SftpSessionEntity session = requireSession(owner, task.sessionId);
        // begin 之前读取快照用于日志和 Content-Length 快速校验，不会改变条目状态。
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
        // HTTP 未知长度时 contentLength 为 -1，仍会由 ExactInputStream 做最终精确校验。
        if (contentLength >= 0 && contentLength != before.size()) throw error("SFTP_INVALID", "请求长度与清单不一致");
        try (Operation op = operation(session, task)) {
            // begin 原子地把 PENDING/FAILED 条目切换到 RUNNING，并拒绝并发上传同一 itemId。
            ItemView item = task.begin(itemId, Direction.UPLOAD);
            // 临时文件和备份文件都放在目标同目录，尽量保证 rename 不跨文件系统。
            String target = RemotePath.join(task.remotePath, item.relativePath());
            String temporary = RemotePath.parent(target) + "/.sftp-" + task.id + "-" + itemId + ".part";
            String backup = RemotePath.parent(target) + "/.sftp-" + task.id + "-" + itemId + ".bak";
            boolean committed = false;
            boolean temporaryOwned = false;
            boolean backupOwned = false;
            try {
                // 保存当前请求流，取消或超时线程可通过 Operation.abort 主动关闭它。
                op.requestStream = input;
                checkParent(op.channel, session, target);
                // 记录上传开始时的目标状态，提交前再次比较以发现并发修改。
                Entry original = op.channel.stat(target);
                if (original != null && (task.conflict != Conflict.REPLACE || original.kind() != Kind.FILE)) {
                    throw error("SFTP_CONFLICT", "上传期间目标已存在且不允许替换，请重新创建任务");
                }
                if (op.channel.stat(temporary) != null) throw error("SFTP_CONFLICT", "临时文件已存在，请创建新任务");
                if (op.channel.stat(backup) != null) throw error("SFTP_CONFLICT", "备份临时文件已存在，请创建新任务");
                // 最多允许读取声明大小再加一个字节，用于同时识别短传和超传。
                ExactInputStream counted = new ExactInputStream(input, item.size(), op);
                temporaryOwned = true;
                op.channel.upload(
                        temporary,
                        counted,
                        delta -> {
                            // count 回调给出增量字节；同时刷新超时活动时间和累计进度。
                            op.touch();
                            task.progress(itemId, delta);
                        },
                        () -> op.cancelled.get() || task.cancelled()
                );
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "实际上传长度与清单不一致");
                // 同时核对客户端实际读取量和服务器临时文件大小，二者正确才允许提交。
                Entry stored = op.channel.stat(temporary);
                if (stored == null || stored.size() != item.size()) throw error("SFTP_SIZE_MISMATCH", "远程文件长度不一致");
                checkParent(op.channel, session, target);
                Entry current = op.channel.stat(target);
                // 比较类型、大小和修改时间，避免并发改写的目标被本任务覆盖。
                boolean targetChanged = original == null
                        ? current != null
                        : current == null
                        || current.kind() != original.kind()
                        || current.size() != original.size()
                        || current.modifiedAt() != original.modifiedAt();
                if (targetChanged) {
                    throw error("SFTP_SOURCE_CHANGED", "目标文件在上传期间发生变化，未执行替换");
                }
                if (current != null) {
                    if (task.conflict != Conflict.REPLACE || current.kind() != Kind.FILE) {
                        throw error("SFTP_CONFLICT", "目标文件已存在，未覆盖");
                    }
                    // 标准 SFTP rename 在不同服务器上的覆盖语义不一致。先把原文件移动到
                    // 本任务专属备份，再提交已经完整校验的新文件；提交失败时恢复原文件。
                    op.channel.rename(target, backup);
                    backupOwned = true;
                }
                try {
                    // .part 改名为最终文件是对外可见的正式提交点。
                    op.channel.rename(temporary, target);
                } catch (RuntimeException commitFailure) {
                    // 原文件已移动但新文件提交失败时，用 .bak 尝试恢复原状态。
                    if (backupOwned && op.channel.stat(target) == null) {
                        try {
                            op.channel.rename(backup, target);
                            backupOwned = false;
                        } catch (RuntimeException rollbackFailure) {
                            log.warn(
                                    "SFTP 替换提交失败且原文件恢复失败 userId={} sessionId={} transferId={} target={} backup={}",
                                    owner,
                                    session.id,
                                    task.id,
                                    target,
                                    backup
                            );
                        }
                    }
                    throw commitFailure;
                }
                committed = true;
                if (backupOwned) {
                    try {
                        op.channel.remove(backup);
                        backupOwned = false;
                    } catch (RuntimeException cleanupFailure) {
                        // 新文件已经提交成功，备份清理失败不能把成功任务改成失败；记录路径供运维清理。
                        log.warn(
                                "SFTP 替换完成但旧文件备份清理失败 userId={} sessionId={} transferId={} backup={}",
                                owner,
                                session.id,
                                task.id,
                                backup
                        );
                    }
                }
                // 只有最终路径已经提交成功后，任务条目才进入 COMPLETED。
                task.finish(itemId, ItemStatus.COMPLETED, null, null);
                log.info(
                        "SFTP 文件上传完成 userId={} sessionId={} transferId={} itemId={} path={} bytes={} replaced={}",
                        owner,
                        session.id,
                        task.id,
                        itemId,
                        target,
                        item.size(),
                        original != null
                );
            } catch (Exception e) {
                // 先把失败原因写入任务快照，再向 HTTP 层抛出稳定业务异常。
                fail(task, itemId, e);
                throw asBusiness(e);
            } finally {
                // 请求结束后不再允许清理线程触碰已经交还 Servlet 容器的输入流。
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

    /**
     * 下载单个清单文件。
     *
     * <p>传输前再次核对远端类型和大小，防止任务创建后文件被替换；发送完成只标记 SENT，
     * 必须等待客户端 confirm 后才算完整成功。</p>
     */
    public void download(String owner, String id, String itemId, OutputStream output) {
        // 与上传相同，所有 owner 校验都沿 transfer -> session 链路完成。
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
            // begin 确认该条目属于下载任务并把状态切换到 RUNNING。
            ItemView item = task.begin(itemId, Direction.DOWNLOAD);
            try {
                // 保存响应流引用，使会话关闭或传输超时时能够解除阻塞写入。
                op.requestStream = output;
                String path = existing(op.channel, session, RemotePath.join(task.remotePath, item.relativePath()));
                // 清单固定后远端文件可能变化，下载前必须再次比较类型和字节数。
                Entry entry = op.channel.stat(path);
                if (entry == null || entry.kind() != Kind.FILE || entry.size() != item.size()) {
                    throw error(
                            "SFTP_SOURCE_CHANGED",
                            "文件在创建任务后发生变化，请重新创建任务"
                    );
                }
                // 限制 JSch 最多写出清单声明的字节数，并累计实际发送量。
                ExactOutputStream counted = new ExactOutputStream(output, item.size(), op);
                op.channel.download(
                        path,
                        counted,
                        delta -> {
                            // JSch 返回本批增量字节，任务实体负责汇总成累计进度。
                            op.touch();
                            task.progress(itemId, delta);
                        },
                        () -> op.cancelled.get() || task.cancelled()
                );
                op.check();
                if (counted.count != item.size()) throw error("SFTP_SIZE_MISMATCH", "下载长度与清单不一致");
                // flush 仅代表数据已交给 HTTP 层，不能证明 Tauri 已成功写入本地磁盘。
                output.flush();
                // 因此先标记 SENT，等待 confirm(saved=true) 后再进入 COMPLETED。
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
                // 失败状态会被 SSE/任务查询看到，异常继续交给统一 HTTP 处理器。
                fail(task, itemId, exception);
                throw asBusiness(exception);
            } finally {
                // 防止清理线程在请求完成后关闭已被容器复用的响应流。
                op.requestStream = null;
            }
        }
    }

    /** 解析一个必须存在的路径，并拒绝越过根目录或通过符号链接逃逸。 */
    private String existing(ISftpClientPort.Channel channel, SftpSessionEntity session, String path) {
        String requested = RemotePath.within(session.rootPath, path);
        checkParent(channel, session, requested);
        Entry entry = channel.stat(requested);
        if (entry == null) throw error("SFTP_NOT_FOUND", "服务器路径不存在");
        if (entry.kind() == Kind.SYMLINK) throw error("SFTP_PATH_FORBIDDEN", "首版不跟随符号链接");
        return RemotePath.within(session.rootPath, channel.realpath(requested));
    }
    /** 校验目标父目录的 realpath 与声明路径一致，阻止父目录符号链接绕过沙箱。 */
    private void checkParent(ISftpClientPort.Channel channel, SftpSessionEntity session, String target) {
        RemotePath.within(session.rootPath, target);
        if (target.equals(session.rootPath)) return;
        String parent = RemotePath.parent(target);
        String real = RemotePath.within(session.rootPath, channel.realpath(parent));
        if (!real.equals(parent)) throw error("SFTP_PATH_FORBIDDEN", "路径包含符号链接，请使用真实目录");
    }
    /** 确保目录存在：缺失时创建，存在且不是目录时报告冲突。 */
    private void ensureDirectory(ISftpClientPort.Channel channel, SftpSessionEntity session, String path) {
        String target = RemotePath.within(session.rootPath, path);
        if (target.equals(session.rootPath)) return;
        checkParent(channel, session, target);
        Entry entry = channel.stat(target);
        if (entry == null) channel.mkdir(target);
        else if (entry.kind() != Kind.DIRECTORY) throw error("SFTP_CONFLICT", "目标路径已存在且不是目录");
    }

    /** 按 ID 和 owner 取得活动会话；对“不存在”和“无权访问”返回相同错误。 */
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
    /** 取得任务并通过其 sessionId 继续完成 owner 校验。 */
    private TransferTaskEntity requireTask(String owner, String id) {
        TransferTaskEntity task = id == null ? null : tasks.get(id);
        if (task == null) {
            log.warn("SFTP 任务校验失败 userId={} transferId={} found=false", owner, id);
            throw error("SFTP_NOT_FOUND", "传输任务不存在或已过期");
        }
        requireSession(owner, task.sessionId);
        return task;
    }
    /** 在创建任务前检查单会话任务保留数量，避免内存任务表无限增长。 */
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
    /** 把异常写入条目最终状态并记录可检索的 owner/session/transfer 上下文。 */
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
    /** 构造无底层 cause 的领域异常。 */
    private static SftpException error(String code, String message) {
        return new SftpException(code, message);
    }
    /** 保留已知业务错误；未知 I/O 异常统一映射为 SFTP_IO_ERROR。 */
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
        /** 绑定所属会话和可选任务，并记录实际执行该操作的请求线程。 */
        Operation(SftpSessionEntity session, TransferTaskEntity task) {
            this.session = session;
            this.task = task;
        }

        /** 收到传输进度时刷新操作与会话的最后活动时间。 */
        void touch() {
            lastProgress = System.currentTimeMillis();
            session.lastActiveAt = lastProgress;
        }

        /** 在关键提交点检查会话关闭、任务取消、超时中断等取消信号。 */
        void check() {
            boolean taskCancelled = task != null && task.cancelled();
            if (cancelled.get()
                    || session.closed
                    || taskCancelled
                    || Thread.currentThread().isInterrupted()) {
                throw error("SFTP_CANCELLED", "文件操作已取消或超时");
            }
        }

        /**
         * 中止阻塞 I/O：先标记取消并中断请求线程，再关闭 Channel 和当前请求流。
         * 方法幂等，清理线程、取消接口和会话关闭可以并发调用。
         */
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

        /** 正常结束操作，关闭独占 Channel 并归还并发名额。 */
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

    /** 无条件关闭会话的便捷入口。 */
    private void closeInternal(SftpSessionEntity session) {
        closeInternal(session, false, 0);
    }
    /**
     * 关闭会话并释放其全部资源。
     *
     * @param idleOnly 为 true 时，仅在没有活动操作且达到空闲超时后关闭
     * @param now      清理任务统一采集的当前时间，避免循环中时间漂移
     */
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

    /** 定期中止超时操作、回收空闲会话，并删除超过保留期的已结束任务。 */
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
    /** Spring 容器退出前关闭全部 SFTP 会话，避免遗留 SSH 连接。 */
    @PreDestroy
    public void shutdown() {
        List.copyOf(sessions.values()).forEach(this::closeInternal);
    }

    /** 最多读取声明长度加一个字节，拒绝短读和超长内容，避免临时文件无限增长。 */
    private static final class ExactInputStream extends FilterInputStream {
        long count;
        final long expected;
        final Operation operation;

        /** 包装 Servlet 输入流，并保存清单声明的精确长度。 */
        ExactInputStream(InputStream input, long expected, Operation operation) {
            super(input);
            this.expected = expected;
            this.operation = operation;
        }

        /** 每次最多多读一个字节，用于同时识别短内容和超长内容。 */
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

        /** 单字节读取复用批量读取逻辑，确保大小和取消检查完全一致。 */
        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            return read(oneByte, 0, 1) < 0 ? -1 : oneByte[0] & 255;
        }

        /** 不关闭容器管理的 Servlet 请求流。 */
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

        /** 包装 Servlet 响应流，并保存任务清单中的远端文件大小。 */
        ExactOutputStream(OutputStream output, long expected, Operation operation) {
            super(output);
            this.expected = expected;
            this.operation = operation;
        }

        /** 写入前限制最大长度，写入后刷新操作活跃时间。 */
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

        /** 单字节写入复用批量写入逻辑，确保长度限制一致。 */
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        /** 只刷新响应流，不提前关闭 Servlet 容器持有的输出流。 */
        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
