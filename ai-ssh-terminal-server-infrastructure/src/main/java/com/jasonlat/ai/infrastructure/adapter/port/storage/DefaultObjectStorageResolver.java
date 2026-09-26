package com.jasonlat.ai.infrastructure.adapter.port.storage;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按实例 ID 注册存储策略；新增 OSS 等实现时不需要在此增加厂商分支。 */
public class DefaultObjectStorageResolver implements ObjectStorageResolver {
    /** 新上传默认使用的实例，由 app 配置装配。 */
    private final String defaultStorageId;
    /** 仅包含已启用实例的只读注册表；注册完成后不再修改。 */
    private final Map<String, ObjectStoragePort> storages;

    public DefaultObjectStorageResolver(String defaultStorageId, List<ObjectStoragePort> ports) {
        this.defaultStorageId = defaultStorageId;
        Map<String, ObjectStoragePort> registered = new HashMap<>();
        for (ObjectStoragePort port : ports) {
            if (!port.enabled()) continue;
            // 实例 ID 不能重复，否则同一个文件位置可能被解析到不同存储。
            if (port.storageId() == null || port.storageId().isBlank()
                    || registered.putIfAbsent(port.storageId(), port) != null) {
                throw new IllegalArgumentException("文件存储 storage-id 为空或重复");
            }
        }
        this.storages = Map.copyOf(registered);
    }

    @Override
    public ObjectStoragePort defaultStorage() {
        return resolve(defaultStorageId);
    }

    @Override
    public ObjectStoragePort resolve(String storageId) {
        ObjectStoragePort storage = storageId == null ? null : storages.get(storageId);
        if (storage == null) {
            // 不自动回退到其他存储，避免配置错误时把文件写进非预期位置。
            throw new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        // 延迟到请求时检查连接参数，让未配置文件存储的应用仍能启动其他功能。
        storage.validateConfiguration();
        return storage;
    }
}
