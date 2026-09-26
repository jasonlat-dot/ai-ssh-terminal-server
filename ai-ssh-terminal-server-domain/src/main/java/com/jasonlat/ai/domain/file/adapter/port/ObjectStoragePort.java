package com.jasonlat.ai.domain.file.adapter.port;

import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** 对象存储策略。MinIO、OSS 等实现放在 infrastructure。 */
public interface ObjectStoragePort {
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

    /** 生成有效时长为 ttl 的签名下载地址；fileName 用于下载命名，不改变对象路径。 */
    URI createDownloadUrl(ObjectLocation location, String fileName, Duration ttl);

    /** 删除不存在的对象视为成功，便于补偿重试。 */
    void delete(ObjectLocation location);
}
