package com.jasonlat.ai.domain.file.model.valobj;

/**
 * 可持久化的对象位置，不保存会过期的签名 URL。
 *
 * @param storageId 存储实例 ID，例如 minio-main，用于选择对应的存储实现
 * @param bucket 对象所在的桶名称
 * @param objectKey 桶内对象路径，例如 uploads/2026-09-26/文件UUID，不是完整 URL
 * @param versionId 存储返回的对象版本号；上传前或未启用版本控制时可能为空
 */
public record ObjectLocation(String storageId, String bucket, String objectKey, String versionId) {
}
