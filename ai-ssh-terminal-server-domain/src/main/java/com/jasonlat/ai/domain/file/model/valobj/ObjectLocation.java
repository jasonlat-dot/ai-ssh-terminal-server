package com.jasonlat.ai.domain.file.model.valobj;

/** 永久存储位置；不保存有过期时间的签名 URL。 */
public record ObjectLocation(String storageId, String bucket, String objectKey, String versionId) {
}
