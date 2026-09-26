package com.jasonlat.ai.infrastructure.adapter.port.storage;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultObjectStorageResolver implements ObjectStorageResolver {
    private final String defaultStorageId;
    private final Map<String, ObjectStoragePort> storages;

    public DefaultObjectStorageResolver(String defaultStorageId, List<ObjectStoragePort> ports) {
        this.defaultStorageId = defaultStorageId;
        Map<String, ObjectStoragePort> registered = new HashMap<>();
        for (ObjectStoragePort port : ports) {
            if (!port.enabled()) continue;
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
            throw new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        storage.validateConfiguration();
        return storage;
    }
}
