package com.jasonlat.ai.domain.sftp.service;

import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 文件管理领域能力；上传下载同步消费流，返回时不再持有调用方的文件流。 */
public interface ISftpService {
    SessionView open(String owner, SshConnectionEntity connection, SshConnectionConfigEntity config, String root);
    SessionView session(String owner, String id);
    void close(String owner, String id);
    List<Entry> list(String owner, String id, String path);
    void mkdir(String owner, String id, String path);
    TransferView create(String owner, CreateTransfer command);
    TransferView task(String owner, String id);
    ItemView downloadItem(String owner, String id, String itemId);
    List<TransferView> tasks(String owner, String sessionId);
    List<Progress> progress(String owner, String sessionId);
    void confirm(String owner, String id, String itemId, boolean saved);
    void cancel(String owner, String id);
    void upload(String owner, String id, String itemId, InputStream input, long contentLength);
    void download(String owner, String id, String itemId, OutputStream output);
}
