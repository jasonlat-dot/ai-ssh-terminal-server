package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** 文件内容存储策略，本机目录、MinIO、OSS 等实现遵循相同读写与下载契约。 */
public interface IObjectStorageService {
    /** 当前存储实例的唯一 ID，与文件元数据中的 storageId 对应。 */
    String storageId();

    /** 是否启用该实例；未启用的实现不参与默认存储选择。 */
    boolean enabled();

    /** 请求时检查配置，未配置存储不能阻止整个应用启动。 */
    void validateConfiguration();

    /** 为后端生成的对象路径补充实例和桶信息，此时尚未发生上传。 */
    ObjectLocation newLocation(String objectKey);

    /**
     * 将指定字节数的文件流写入对象存储，返回实际位置、版本号及 ETag。
     * 输入流由调用方关闭，实现不应将整个文件一次性读入内存。
     */
    StoredObject put(ObjectLocation location, InputStream input, long size);

    /** 按持久化位置读取对象；启用版本控制时读取原版本，返回流由调用方关闭。 */
    InputStream openRead(ObjectLocation location);

    /**
     * 生成有效时长为 ttl 的下载地址；fileName 用于下载命名，不改变对象路径。
     * 对象服务可以返回预签名地址，本地存储返回后端自身的临时签名接口。
     */
    URI createDownloadUrl(ObjectLocation location, String fileName, Duration ttl);

    /** 删除不存在的对象视为成功，便于补偿重试。 */
    void delete(ObjectLocation location);
}
