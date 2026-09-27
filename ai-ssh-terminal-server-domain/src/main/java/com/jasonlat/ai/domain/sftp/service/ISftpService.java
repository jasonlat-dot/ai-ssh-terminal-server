package com.jasonlat.ai.domain.sftp.service;

import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 文件管理领域能力。
 *
 * <p>上传和下载方法会在当前调用线程中同步消费文件流，方法返回后不再持有调用方传入的流。
 * 会话、任务与条目均按 owner 做归属校验。</p>
 */
public interface ISftpService {

    SessionView open(
            String owner,
            SshConnectionEntity connection,
            SshConnectionConfigEntity config,
            String root
    );

    SessionView session(String owner, String id);

    void close(String owner, String id);

    List<Entry> list(String owner, String id, String path);

    void mkdir(String owner, String id, String path);

    /** 创建一个空文件；目标已存在时不得覆盖。 */
    void createFile(String owner, String id, String path);

    /** 删除普通文件或空目录；会话根目录和非空目录不得删除。 */
    void delete(String owner, String id, String path);

    TransferView create(String owner, CreateTransfer command);

    TransferView task(String owner, String id);

    ItemView downloadItem(String owner, String id, String itemId);

    List<TransferView> tasks(String owner, String sessionId);

    List<Progress> progress(String owner, String sessionId);

    void confirm(String owner, String id, String itemId, boolean saved);

    void cancel(String owner, String id);

    /** 同步接收请求流并写入远程临时文件，校验通过后再提交最终文件。 */
    void upload(String owner, String id, String itemId, InputStream input, long contentLength);

    /** 同步读取远程文件并写入 HTTP 响应流。 */
    void download(String owner, String id, String itemId, OutputStream output);
}
