package com.jasonlat.ai.domain.file.model.valobj;

/**
 * 存储适配器返回的写入结果，隔离本地文件系统、MinIO、OSS 等具体类型。
 *
 * @param location 上传完成后的实际存储位置，包含存储返回的版本号
 * @param etag 存储返回的对象标识；本地存储可能为空，不能假定为 MD5，文件摘要由领域服务单独计算
 */
public record StoredObject(ObjectLocation location, String etag) {
}
