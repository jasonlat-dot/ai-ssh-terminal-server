package com.jasonlat.ai.infrastructure.adapter.port.sftp;

import com.jasonlat.ai.domain.sftp.adapter.port.ISftpClientPort;
import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.RemotePath;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Entry;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Kind;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpPolicy;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.infrastructure.model.settings.SshHttpProxySettings;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpProgressMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 基于 mwiede JSch 的 SFTP 客户端适配器。
 *
 * <p>该类只负责把领域层定义的文件操作转换为 JSch 调用，并把 SDK 异常翻译为稳定的业务错误码。
 * SFTP 连接拥有独立生命周期，不注册到终端 Session 表，因此关闭终端不会中断仍在进行的文件传输。</p>
 *
 * <p>一个 {@link ISftpClientPort.Connection} 对应一条 SSH 连接。每次文件操作会创建独立的
 * SFTP Channel，调用方使用完后必须关闭 Channel。</p>
 */
@Component
@Slf4j
public class JschSftpClientPort implements ISftpClientPort {

    private static final int SERVER_ALIVE_INTERVAL_MILLIS = 30_000;
    private static final int SERVER_ALIVE_COUNT_MAX = 5;

    private final SshHttpProxySettings proxy;
    private final int connectTimeout;

    /**
     * 创建 JSch 适配器。
     *
     * <p>复用应用的 HTTP 代理配置；连接超时使用 SFTP operationTimeout，避免 SDK 默认值
     * 导致请求无限等待。</p>
     */
    public JschSftpClientPort(SshHttpProxySettings proxy, SftpPolicy policy) {
        this.proxy = proxy;
        this.connectTimeout = (int) Math.min(
                Integer.MAX_VALUE,
                policy.operationTimeout().toMillis()
        );
    }

    /**
     * 根据已保存的 SSH 凭据建立一条专用于文件管理的连接。
     *
     * <p>启用严格主机密钥校验时，必须同时提供 OpenSSH known_hosts 内容。认证优先使用私钥，
     * 未配置私钥时再使用密码；两者都不存在时直接拒绝连接。</p>
     */
    @Override
    public ISftpClientPort.Connection connect(
            SshConnectionEntity credentials,
            SshConnectionConfigEntity config
    ) {
        Session session = null;
        log.info(
                "SFTP JSch 开始连接 connectionId={} sshUser={} host={} port={}",
                credentials.getConnectionId(),
                credentials.getUsername(),
                credentials.getHost(),
                credentials.getPort()
        );

        try {
            // 每次文件管理会话创建独立 JSch 实例，known_hosts 和私钥配置不会在用户之间共享。
            JSch jsch = new JSch();
            boolean strictHostKeyCheck = isStrictHostKeyCheckEnabled(config);

            // known_hosts 必须在 Session.connect 之前配置，JSch 才能在 SSH 握手时校验服务器身份。
            configureKnownHosts(jsch, config, strictHostKeyCheck);

            // getSession 只创建配置对象，真正的 TCP/SSH 握手发生在后面的 session.connect。
            session = jsch.getSession(
                    credentials.getUsername(),
                    credentials.getHost(),
                    credentials.getPort()
            );
            // 严格模式拒绝未知或发生变化的主机密钥；关闭时由 JSch 接受当前服务器密钥。
            session.setConfig("StrictHostKeyChecking", strictHostKeyCheck ? "yes" : "no");

            // 认证、代理和心跳都必须在连接前装入 Session。
            configureAuthentication(jsch, session, credentials);
            configureProxy(session);
            configureKeepAlive(session);

            // connectTimeout 同时限制 TCP 建连与 SSH 握手等待时间。
            session.connect(connectTimeout);
            log.info(
                    "SFTP JSch 连接成功 connectionId={} sshUser={} host={} port={} serverVersion={}",
                    credentials.getConnectionId(),
                    credentials.getUsername(),
                    credentials.getHost(),
                    credentials.getPort(),
                    session.getServerVersion()
            );
            // 此时只有 SSH Session；具体 SFTP Channel 在每次文件操作开始时按需创建。
            return new JschConnection(session, connectTimeout);
        } catch (Exception exception) {
            log.warn(
                    "SFTP JSch 连接失败 connectionId={} sshUser={} host={} port={} errorType={} message={}",
                    credentials.getConnectionId(),
                    credentials.getUsername(),
                    credentials.getHost(),
                    credentials.getPort(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            if (session != null) {
                session.disconnect();
            }
            throw translateConnectionException(exception);
        }
    }

    /** 读取连接级严格主机密钥开关；历史连接没有配置时按关闭处理。 */
    private static boolean isStrictHostKeyCheckEnabled(SshConnectionConfigEntity config) {
        return config != null && Boolean.TRUE.equals(config.getStrictHostKeyCheck());
    }

    /**
     * 将前端保存的 known_hosts 文本直接交给 JSch 解析。
     *
     * <p>这里接收完整的 OpenSSH 行，例如：
     * {@code 192.168.3.16 ssh-ed25519 AAAAC3...}。不自行拆分字段，避免破坏 hashed host、
     * 多主机名和带端口主机名等 OpenSSH 支持的格式。</p>
     */
    private static void configureKnownHosts(
            JSch jsch,
            SshConnectionConfigEntity config,
            boolean strictHostKeyCheck
    ) throws JSchException {
        String knownHosts = config == null ? null : config.getKnownHosts();

        if (strictHostKeyCheck && (knownHosts == null || knownHosts.isBlank())) {
            throw new SftpException(
                    "SFTP_HOST_KEY_REQUIRED",
                    "该连接已开启严格主机密钥检查，请先配置服务器 known_hosts 主机密钥"
            );
        }

        if (knownHosts != null && !knownHosts.isBlank()) {
            // JSch 原生解析 OpenSSH known_hosts 文本，保留 hashed host 和 [host]:port 等语义。
            byte[] content = knownHosts.getBytes(StandardCharsets.UTF_8);
            jsch.setKnownHosts(new ByteArrayInputStream(content));
        }
    }

    /**
     * 将服务端保存的认证信息装入 JSch。
     *
     * <p>私钥直接来自数据库中的 OpenSSH/PEM 文本；mwiede JSch 会根据密钥头识别
     * RSA、ECDSA、Ed25519 等类型。当前数据模型没有私钥口令字段，因此这里只处理无口令私钥。</p>
     */
    private static void configureAuthentication(
            JSch jsch,
            Session session,
            SshConnectionEntity credentials
    ) throws JSchException {
        String privateKey = credentials.getPrivateKey();
        String password = credentials.getPassword();

        if (privateKey != null && !privateKey.isBlank()) {
            // addIdentity 接收的是私钥正文，不是服务器主机密钥；二者用途完全不同。
            jsch.addIdentity(
                    "sftp",
                    privateKey.getBytes(StandardCharsets.UTF_8),
                    null,
                    null
            );
            return;
        }

        if (password != null && !password.isBlank()) {
            // 未配置私钥时才回退密码认证，避免同一连接出现不明确的认证优先级。
            session.setPassword(password);
            return;
        }

        throw new SftpException("SFTP_INVALID", "SSH 连接未配置认证信息");
    }

    /** 按应用配置为 SSH Session 安装 HTTP CONNECT 代理；未启用时保持直连。 */
    private void configureProxy(Session session) {
        if (!proxy.enabled()) {
            return;
        }

        ProxyHTTP tunnel = new ProxyHTTP(proxy.host(), proxy.port());
        if (proxy.username() != null && !proxy.username().isBlank()) {
            // 这里只是 HTTP CONNECT 代理认证，不会替代目标 SSH 服务器的账号认证。
            tunnel.setUserPasswd(proxy.username(), proxy.password());
        }
        session.setProxy(tunnel);
    }

    /** 配置 SSH 层心跳，用于发现已失效但 TCP 尚未及时报错的连接。 */
    private static void configureKeepAlive(Session session) throws JSchException {
        // 心跳用于尽早识别网络断开；超过最大连续失败次数后由 JSch 终止连接。
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MILLIS);
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
    }

    /** 把建连阶段的 SDK 异常翻译成稳定业务错误，Controller 不直接依赖 JSch 异常类型。 */
    private static SftpException translateConnectionException(Exception exception) {
        if (exception instanceof SftpException businessException) {
            return businessException;
        }

        if (exception instanceof JSchException
                && exception.getMessage() != null
                && exception.getMessage().contains("HostKey")) {
            return new SftpException(
                    "SFTP_HOST_KEY_REJECTED",
                    "服务器主机密钥校验失败，请核对连接配置中的 known_hosts",
                    exception
            );
        }

        return new SftpException(
                "SFTP_UNAVAILABLE",
                "SFTP 建连失败，请检查连接配置及服务器 SFTP 服务",
                exception
        );
    }

    /** SSH 连接包装器。每次调用 {@link #channel()} 都创建一个新的 SFTP Channel。 */
    private static final class JschConnection implements ISftpClientPort.Connection {

        private final Session session;
        private final int connectTimeout;

        /** 保存一条已认证的 SSH Session；这里还没有打开 SFTP Channel。 */
        private JschConnection(Session session, int connectTimeout) {
            this.session = session;
            this.connectTimeout = connectTimeout;
        }

        /** 为单次目录操作或传输创建并连接一个全新的 SFTP Channel。 */
        @Override
        public ISftpClientPort.Channel channel() {
            ChannelSftp channel = null;
            try {
                // 一个 SSH Session 可以承载多个 Channel；每个操作独占 Channel，避免并发调用 ChannelSftp。
                channel = (ChannelSftp) session.openChannel("sftp");
                // openChannel 只创建本地对象，connect 才会向服务器申请 SFTP 子系统。
                channel.connect(connectTimeout);
                return new JschSftpChannel(channel);
            } catch (JSchException exception) {
                if (channel != null) {
                    channel.disconnect();
                }
                throw new SftpException(
                        "SFTP_UNAVAILABLE",
                        "无法打开 SFTP 通道",
                        exception
                );
            }
        }

        /** 只检查 JSch SSH Session 状态，不会额外发送网络请求。 */
        @Override
        public boolean connected() {
            return session.isConnected();
        }

        /** 断开底层 SSH Session；其上创建的全部 SFTP Channel 会随之失效。 */
        @Override
        public void close() {
            session.disconnect();
        }
    }

    /** 单个 JSch SFTP Channel 的领域端口实现，不持有业务任务状态。 */
    private static final class JschSftpChannel implements ISftpClientPort.Channel {

        private final ChannelSftp client;

        /** 包装一个已经连接的 JSch ChannelSftp。 */
        private JschSftpChannel(ChannelSftp client) {
            this.client = client;
        }

        /** 调用 SFTP REALPATH，让服务器解析账号默认目录、相对路径和规范绝对路径。 */
        @Override
        public String realpath(String path) {
            try {
                return client.realpath(path);
            } catch (com.jcraft.jsch.SftpException exception) {
                throw translateOperationException(exception);
            }
        }

        /** 使用 lstat 查询条目本身，避免自动跟随最后一级符号链接。 */
        @Override
        public Entry stat(String path) {
            try {
                // 使用 lstat 而不是 stat，确保最后一级符号链接仍以 SYMLINK 返回。
                return toEntry(RemotePath.name(path), path, client.lstat(path));
            } catch (com.jcraft.jsch.SftpException exception) {
                // stat 的领域契约约定“不存在”返回 null，其他错误仍然向上抛出。
                if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return null;
                }
                throw translateOperationException(exception);
            }
        }

        /** 使用 JSch selector 流式枚举目录，并在达到配置上限后主动停止。 */
        @Override
        public List<Entry> list(String path, int limit) {
            List<Entry> entries = new ArrayList<>();

            try {
                // Selector 可以在达到上限后中止扫描，避免 SDK 为超大目录一次性分配完整列表。
                client.ls(path, selected -> {
                    String name = selected.getFilename();
                    if (!".".equals(name) && !"..".equals(name)) {
                        entries.add(toEntry(
                                name,
                                RemotePath.join(path, name),
                                selected.getAttrs()
                        ));
                    }

                    return entries.size() > limit
                            ? ChannelSftp.LsEntrySelector.BREAK
                            : ChannelSftp.LsEntrySelector.CONTINUE;
                });

                if (entries.size() > limit) {
                    throw new SftpException(
                            "SFTP_LIMIT",
                            "目录条目超过配置上限，请进入更小的子目录操作"
                    );
                }

                entries.sort(
                        Comparator.comparing((Entry entry) -> entry.kind() != Kind.DIRECTORY)
                                .thenComparing(Entry::name)
                );
                return List.copyOf(entries);
            } catch (com.jcraft.jsch.SftpException exception) {
                throw translateOperationException(exception);
            }
        }

        /** 调用 SFTP MKDIR 创建单级目录；父目录必须已经存在。 */
        @Override
        public void mkdir(String path) {
            try {
                log.info("SFTP JSch mkdir 开始 path={}", path);
                client.mkdir(path);
                log.info("SFTP JSch mkdir 完成 path={}", path);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn("SFTP JSch mkdir 失败 path={} status={} message={}", path, exception.id, exception.getMessage());
                throw translateOperationException(exception);
            }
        }

        /** 调用 SFTP REMOVE 删除普通文件；目录不能走这个方法。 */
        @Override
        public void remove(String path) {
            try {
                log.info("SFTP JSch rm 开始 path={}", path);
                client.rm(path);
                log.info("SFTP JSch rm 完成 path={}", path);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn("SFTP JSch rm 失败 path={} status={} message={}", path, exception.id, exception.getMessage());
                throw translateOperationException(exception);
            }
        }

        /** 调用 SFTP RMDIR 删除空目录；协议本身不会递归删除。 */
        @Override
        public void rmdir(String path) {
            try {
                log.info("SFTP JSch rmdir 开始 path={}", path);
                client.rmdir(path);
                log.info("SFTP JSch rmdir 完成 path={}", path);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn("SFTP JSch rmdir 失败 path={} status={} message={}", path, exception.id, exception.getMessage());
                throw translateOperationException(exception);
            }
        }

        /** 在同一服务器内重命名，用于把校验完成的临时文件提交到最终路径。 */
        @Override
        public void rename(String source, String target) {
            try {
                log.info("SFTP JSch rename 开始 source={} target={}", source, target);
                client.rename(source, target);
                log.info("SFTP JSch rename 完成 source={} target={}", source, target);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn(
                        "SFTP JSch rename 失败 source={} target={} status={} message={}",
                        source,
                        target,
                        exception.id,
                        exception.getMessage()
                );
                throw translateOperationException(exception);
            }
        }

        /**
         * 使用 JSch put 流式上传。
         *
         * <p>这里的 OVERWRITE 只允许覆盖领域层生成的临时文件；最终目标是否替换由
         * Conflict 策略和领域层提交步骤决定。</p>
         */
        @Override
        public void upload(
                String path,
                InputStream input,
                LongConsumer progress,
                BooleanSupplier cancelled
        ) {
            try {
                log.info("SFTP JSch put 开始 path={}", path);
                // JSch 直接从 InputStream 分块读取；不会把整个文件加载到 JVM 堆内存。
                client.put(
                        input,
                        path,
                        createProgressMonitor(progress, cancelled),
                        // 该 path 是领域层生成的 .part 临时文件，允许覆盖的是临时目标而非最终用户文件。
                        ChannelSftp.OVERWRITE
                );
                log.info("SFTP JSch put 完成 path={}", path);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn("SFTP JSch put 失败 path={} status={} message={}", path, exception.id, exception.getMessage());
                throw translateOperationException(exception);
            }
        }

        /** 使用 JSch get 把远程文件流式写入调用方输出流，不在内存中缓存完整文件。 */
        @Override
        public void download(
                String path,
                OutputStream output,
                LongConsumer progress,
                BooleanSupplier cancelled
        ) {
            try {
                log.info("SFTP JSch get 开始 path={}", path);
                // JSch 读取到的字节直接写入 Servlet OutputStream，并通过 monitor 累加进度。
                client.get(path, output, createProgressMonitor(progress, cancelled));
                log.info("SFTP JSch get 完成 path={}", path);
            } catch (com.jcraft.jsch.SftpException exception) {
                log.warn("SFTP JSch get 失败 path={} status={} message={}", path, exception.id, exception.getMessage());
                throw translateOperationException(exception);
            }
        }

        /** 断开本次操作独占的 SFTP Channel，不影响同一 SSH Session 上的其他操作。 */
        @Override
        public void close() {
            client.disconnect();
        }

        /**
         * 创建轻量级进度监听器。
         *
         * <p>JSch 会在每次传输一批字节后调用 {@code count}。返回 {@code false} 会让 SDK
         * 尽快停止当前传输，因此这里同时承担进度上报和协作式取消检查。</p>
         *
         * <p>{@code end} 只表示 JSch 已结束流复制，并不等价于业务成功。业务层还需要检查
         * 实际字节数、完成临时文件校验，并将临时文件原子提交到最终路径。</p>
         */
        private static SftpProgressMonitor createProgressMonitor(
                LongConsumer progress,
                BooleanSupplier cancelled
        ) {
            return new SftpProgressMonitor() {
                @Override
                public void init(int operation, String source, String destination, long maximum) {
                    // 清单中的预期大小由领域层维护，此处无需重复初始化总字节数。
                }

                @Override
                public boolean count(long transferredBytes) {
                    // JSch 传入的是本批增量而不是累计值，领域任务负责把每批增量累加。
                    progress.accept(transferredBytes);
                    // 返回 false 是 JSch SftpProgressMonitor 约定的协作式取消方式。
                    return !cancelled.getAsBoolean();
                }

                @Override
                public void end() {
                    // 最终成功状态由领域层完成长度校验和文件提交后决定。
                }
            };
        }

        /** 将 JSch 的 SftpATTRS 转换为不依赖 SDK 的领域 Entry。 */
        private static Entry toEntry(String name, String path, SftpATTRS attributes) {
            Kind kind;
            if (attributes.isLink()) {
                kind = Kind.SYMLINK;
            } else if (attributes.isDir()) {
                kind = Kind.DIRECTORY;
            } else if (attributes.isReg()) {
                kind = Kind.FILE;
            } else {
                kind = Kind.OTHER;
            }

            return new Entry(
                    name,
                    path,
                    kind,
                    attributes.getSize(),
                    Integer.toUnsignedLong(attributes.getMTime()) * 1_000,
                    attributes.getPermissionsString()
            );
        }

        /** 将 JSch 的协议状态码统一转换为前端可识别的业务错误码。 */
        private static SftpException translateOperationException(
                com.jcraft.jsch.SftpException exception
        ) {
            return switch (exception.id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE -> new SftpException(
                        "SFTP_NOT_FOUND",
                        "服务器文件或目录不存在",
                        exception
                );
                case ChannelSftp.SSH_FX_PERMISSION_DENIED -> new SftpException(
                        "SFTP_FORBIDDEN",
                        "SSH 用户没有该文件的访问权限",
                        exception
                );
                default -> new SftpException(
                        "SFTP_IO_ERROR",
                        "SFTP 操作失败",
                        exception
                );
            };
        }
    }
}
