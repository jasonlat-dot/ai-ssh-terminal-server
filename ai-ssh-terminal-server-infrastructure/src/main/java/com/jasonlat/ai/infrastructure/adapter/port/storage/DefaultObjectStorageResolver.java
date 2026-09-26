package com.jasonlat.ai.infrastructure.adapter.port.storage;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.infrastructure.config.FileStorageProperties;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultObjectStorageResolver implements ObjectStorageResolver {
    private final FileStorageProperties properties;
    private final Map<String, ObjectStoragePort> storages;

    public DefaultObjectStorageResolver(FileStorageProperties properties, List<ObjectStoragePort> ports) {
        this.properties = properties;
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
        return resolve(properties.getDefaultId());
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
