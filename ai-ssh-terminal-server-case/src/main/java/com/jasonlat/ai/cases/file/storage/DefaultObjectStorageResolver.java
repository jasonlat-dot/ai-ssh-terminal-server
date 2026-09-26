package com.jasonlat.ai.cases.file.storage;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用层存储选择器：按实例 ID 注册策略，决定本次用例使用哪个存储。
 * 仅依赖领域端口；新增 OSS 等实现时无需增加厂商分支，也不依赖具体 SDK。
 * 由 app 配置类装配，避免组件扫描重复注册。
 */
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
        // 默认配置只影响新上传，旧文件应调用 resolve 并传入持久化的 storageId。
        return resolve(defaultStorageId);
    }

    @Override
    public ObjectStoragePort resolve(String storageId) {
        ObjectStoragePort storage = storageId == null ? null : storages.get(storageId);
        if (storage == null) {
            // 不自动回退到其他存储，避免配置错误时把文件写进非预期位置。
            throw new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        // 参数合法性由具体适配器检查；此处只在选中后触发，不在启动时连接外部存储。
        storage.validateConfiguration();
        return storage;
    }
}
