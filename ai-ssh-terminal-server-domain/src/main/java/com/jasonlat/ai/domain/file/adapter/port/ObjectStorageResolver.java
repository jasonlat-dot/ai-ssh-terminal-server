package com.jasonlat.ai.domain.file.adapter.port;

/** 默认存储用于新上传；已有文件必须根据它自己的 storageId 定位。 */
public interface ObjectStorageResolver {
    ObjectStoragePort defaultStorage();

    ObjectStoragePort resolve(String storageId);
}
