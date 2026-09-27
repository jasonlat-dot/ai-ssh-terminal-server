package com.jasonlat.ai.infrastructure.adapter.port.sftp;

import com.jasonlat.ai.domain.sftp.adapter.port.ISftpClientPort;
import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.RemotePath;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpPolicy;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.infrastructure.model.settings.SshHttpProxySettings;
import com.jcraft.jsch.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** SDK 适配器。SFTP 连接不注册到终端 Session 表，终端关闭不会打断文件传输。 */
@Component
public class JschSftpClientPort implements ISftpClientPort {
    private final SshHttpProxySettings proxy;
    private final int connectTimeout;
    public JschSftpClientPort(SshHttpProxySettings proxy, SftpPolicy policy) {
        this.proxy = proxy;
        this.connectTimeout = (int) Math.min(Integer.MAX_VALUE, policy.operationTimeout().toMillis());
    }

    @Override
    public Connection connect(SshConnectionEntity credentials, SshConnectionConfigEntity config) {
        Session session = null;
        try {
            JSch jsch = new JSch();
            if (config != null && config.getKnownHosts() != null && !config.getKnownHosts().isBlank()) {
                jsch.setKnownHosts(new ByteArrayInputStream(config.getKnownHosts().getBytes(StandardCharsets.UTF_8)));
            }
            session = jsch.getSession(credentials.getUsername(), credentials.getHost(), credentials.getPort());
            session.setConfig("StrictHostKeyChecking", config != null && Boolean.TRUE.equals(config.getStrictHostKeyCheck()) ? "yes" : "no");
            if (credentials.getPrivateKey() != null && !credentials.getPrivateKey().isBlank()) {
                jsch.addIdentity("sftp", credentials.getPrivateKey().getBytes(StandardCharsets.UTF_8), null, null);
            } else if (credentials.getPassword() != null && !credentials.getPassword().isBlank()) {
                session.setPassword(credentials.getPassword());
            } else throw new SftpException("SFTP_INVALID", "SSH 连接未配置认证信息");
            if (proxy.enabled()) {
                ProxyHTTP tunnel = new ProxyHTTP(proxy.host(), proxy.port());
                if (proxy.username() != null && !proxy.username().isBlank()) tunnel.setUserPasswd(proxy.username(), proxy.password());
                session.setProxy(tunnel);
            }
            session.setServerAliveInterval(30_000);
            session.setServerAliveCountMax(5);
            session.connect(connectTimeout);
            Session connected = session;
            return new Connection() {
                @Override public Channel channel() {
                    ChannelSftp channel = null;
                    try {
                        channel = (ChannelSftp) connected.openChannel("sftp");
                        channel.connect(connectTimeout);
                        return new SftpChannel(channel);
                    } catch (JSchException e) {
                        if (channel != null) channel.disconnect();
                        throw new SftpException("SFTP_UNAVAILABLE", "无法打开 SFTP 通道", e);
                    }
                }
                @Override public boolean connected() { return connected.isConnected(); }
                @Override public void close() { connected.disconnect(); }
            };
        } catch (Exception e) {
            if (session != null) session.disconnect();
            if (e instanceof SftpException business) throw business;
            throw new SftpException("SFTP_UNAVAILABLE", "SFTP 建连失败，请检查连接配置及服务器 SFTP 服务", e);
        }
    }

    private static final class SftpChannel implements Channel {
        private final ChannelSftp client;
        private SftpChannel(ChannelSftp client) { this.client = client; }
        @Override public String realpath(String path) {
            try { return client.realpath(path); } catch (com.jcraft.jsch.SftpException e) { throw translate(e); }
        }
        @Override public Entry stat(String path) {
            try { return entry(RemotePath.name(path), path, client.lstat(path)); }
            catch (com.jcraft.jsch.SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
                throw translate(e);
            }
        }
        @Override public List<Entry> list(String path, int limit) {
            List<Entry> entries = new ArrayList<>();
            try {
                // Selector 可提前终止超大目录扫描，避免 SDK 一次性分配整个目录列表。
                client.ls(path, selected -> {
                    String name = selected.getFilename();
                    if (!name.equals(".") && !name.equals("..")) {
                        entries.add(entry(name, RemotePath.join(path, name), selected.getAttrs()));
                    }
                    return entries.size() > limit ? ChannelSftp.LsEntrySelector.BREAK : ChannelSftp.LsEntrySelector.CONTINUE;
                });
                if (entries.size() > limit) throw new SftpException("SFTP_LIMIT", "目录条目超过配置上限，请进入更小的子目录操作");
                entries.sort(Comparator.comparing((Entry e) -> e.kind() != Kind.DIRECTORY).thenComparing(Entry::name));
                return List.copyOf(entries);
            } catch (com.jcraft.jsch.SftpException e) { throw translate(e); }
        }
        @Override public void mkdir(String path) { try { client.mkdir(path); } catch (com.jcraft.jsch.SftpException e) { throw translate(e); } }
        @Override public void remove(String path) { try { client.rm(path); } catch (com.jcraft.jsch.SftpException e) { throw translate(e); } }
        @Override public void rename(String source, String target) { try { client.rename(source, target); } catch (com.jcraft.jsch.SftpException e) { throw translate(e); } }
        @Override public void upload(String path, InputStream input, LongConsumer progress, BooleanSupplier cancelled) {
            try { client.put(input, path, monitor(progress, cancelled), ChannelSftp.OVERWRITE); }
            catch (com.jcraft.jsch.SftpException e) { throw translate(e); }
        }
        @Override public void download(String path, OutputStream output, LongConsumer progress, BooleanSupplier cancelled) {
            try { client.get(path, output, monitor(progress, cancelled)); }
            catch (com.jcraft.jsch.SftpException e) { throw translate(e); }
        }
        @Override public void close() { client.disconnect(); }
        private static SftpProgressMonitor monitor(LongConsumer progress, BooleanSupplier cancelled) {
            return new SftpProgressMonitor() {
                @Override public void init(int op, String src, String dest, long max) { }
                @Override public boolean count(long delta) { progress.accept(delta); return !cancelled.getAsBoolean(); }
                @Override public void end() { /* 最终成功由业务层检查字节数并提交文件后决定。 */ }
            };
        }
        private static Entry entry(String name, String path, SftpATTRS attrs) {
            Kind kind = attrs.isLink() ? Kind.SYMLINK : attrs.isDir() ? Kind.DIRECTORY : attrs.isReg() ? Kind.FILE : Kind.OTHER;
            return new Entry(name, path, kind, attrs.getSize(), Integer.toUnsignedLong(attrs.getMTime()) * 1000, attrs.getPermissionsString());
        }
        private static SftpException translate(com.jcraft.jsch.SftpException e) {
            return switch (e.id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE -> new SftpException("SFTP_NOT_FOUND", "服务器文件或目录不存在", e);
                case ChannelSftp.SSH_FX_PERMISSION_DENIED -> new SftpException("SFTP_FORBIDDEN", "SSH 用户没有该文件的访问权限", e);
                default -> new SftpException("SFTP_IO_ERROR", "SFTP 操作失败", e);
            };
        }
    }
}
