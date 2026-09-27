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

    /** 使用已验证归属的 SSH 配置建立独立文件管理会话，并固定允许访问的根目录。 */
    SessionView open(
            String owner,
            SshConnectionEntity connection,
            SshConnectionConfigEntity config,
            String root
    );

    /** 查询文件管理会话快照。 */
    SessionView session(String owner, String id);

    /** 关闭文件管理会话并释放 SSH 连接、Channel 和任务。 */
    void close(String owner, String id);

    /** 列出会话根目录范围内的远程目录。 */
    List<Entry> list(String owner, String id, String path);

    /** 创建单级远程目录。 */
    void mkdir(String owner, String id, String path);

    /** 创建一个空文件；目标已存在时不得覆盖。 */
    void createFile(String owner, String id, String path);

    /** 删除普通文件或空目录；会话根目录和非空目录不得删除。 */
    void delete(String owner, String id, String path);

    /** 创建上传或下载任务清单，但此时不传输文件内容。 */
    TransferView create(String owner, CreateTransfer command);

    /** 查询一个传输任务的完整快照。 */
    TransferView task(String owner, String id);

    /** 查询下载响应头所需的单个条目元数据。 */
    ItemView downloadItem(String owner, String id, String itemId);

    /** 查询文件管理会话下的全部传输任务。 */
    List<TransferView> tasks(String owner, String sessionId);

    /** 读取 SSE 展示所需的轻量进度快照。 */
    List<Progress> progress(String owner, String sessionId);

    /** 确认客户端下载内容是否成功落盘。 */
    void confirm(String owner, String id, String itemId, boolean saved);

    /** 请求取消任务及其正在进行的网络操作。 */
    void cancel(String owner, String id);

    /** 同步接收请求流并写入远程临时文件，校验通过后再提交最终文件。 */
    void upload(String owner, String id, String itemId, InputStream input, long contentLength);

    /** 同步读取远程文件并写入 HTTP 响应流。 */
    void download(String owner, String id, String itemId, OutputStream output);
}
