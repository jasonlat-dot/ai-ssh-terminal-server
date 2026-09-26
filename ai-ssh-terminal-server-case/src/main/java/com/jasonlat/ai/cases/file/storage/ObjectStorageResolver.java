package com.jasonlat.ai.cases.file.storage;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;

/** 应用层的存储选择契约；新上传使用默认实例，已有文件按自身 storageId 定位。 */
public interface ObjectStorageResolver {
    /** 获取新上传使用的默认实例，并检查配置；不可用时抛出明确业务异常。 */
    ObjectStoragePort defaultStorage();

    /** 根据持久化的实例 ID 定位存储，不因默认存储切换而改变旧文件的读取位置。 */
    ObjectStoragePort resolve(String storageId);
}
