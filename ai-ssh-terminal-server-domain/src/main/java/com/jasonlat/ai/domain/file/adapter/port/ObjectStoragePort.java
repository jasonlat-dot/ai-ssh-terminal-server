package com.jasonlat.ai.domain.file.adapter.port;

import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** 对象存储策略。MinIO、OSS 等实现放在 infrastructure。 */
public interface ObjectStoragePort {
    String storageId();

    boolean enabled();

    /** 请求时检查配置，未配置存储不能阻止整个应用启动。 */
    void validateConfiguration();

    ObjectLocation newLocation(String objectKey);

    /** 流的生命周期由调用方管理；实现不允许将整个文件读入内存。 */
    StoredObject put(ObjectLocation location, InputStream input, long size);

    URI createDownloadUrl(ObjectLocation location, String fileName, Duration ttl);

    /** 删除不存在的对象视为成功，便于补偿重试。 */
    void delete(ObjectLocation location);
}
