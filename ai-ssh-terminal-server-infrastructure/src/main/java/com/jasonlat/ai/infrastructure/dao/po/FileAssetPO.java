package com.jasonlat.ai.infrastructure.dao.po;

import lombok.Data;

/** file_asset 表映射；存储位置拆成独立字段，便于迁移和失败对象排查。 */
@Data
public class FileAssetPO {
    private String fileId;
    private String ownerId;
    private String originalName;
    private String contentType;
    private long size;
    private String sha256;
    private String storageId;
    private String bucket;
    private String objectKey;
    private String objectVersion;
    private String etag;
    private String status;
    private String errorCode;
}
