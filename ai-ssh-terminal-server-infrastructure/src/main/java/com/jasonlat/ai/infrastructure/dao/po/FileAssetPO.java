package com.jasonlat.ai.infrastructure.dao.po;

import lombok.Data;

/** file_asset 表映射；存储位置拆成独立字段，便于迁移和失败对象排查。 */
@Data
public class FileAssetPO {
    /** 文件主键，对应 file_id，由后端生成 UUID。 */
    private String fileId;
    /** 上传者的认证身份，对应 owner_id；未接入认证时可为空。 */
    private String ownerId;
    /** 去除客户端路径后的展示文件名，对应 original_name。 */
    private String originalName;
    /** 规范化后的客户端声明 MIME 类型，不是实际内容检测结果。 */
    private String contentType;
    /** 文件字节数，对应数据库 size_bytes。 */
    private long size;
    /** 后端计算的 SHA-256 摘要，完成计算前为空。 */
    private String sha256;
    /** 存储实例 ID，例如 minio-main；切换默认存储不应覆盖已有记录的实例 ID。 */
    private String storageId;
    /** 对象所在的桶名称。 */
    private String bucket;
    /** 桶内的唯一对象路径，由后端生成，不包含签名参数。 */
    private String objectKey;
    /** 存储返回的对象版本号；未启用版本控制或未获得上传结果时可能为空。 */
    private String objectVersion;
    /** 存储返回的对象 ETag，不保证等于文件 MD5。 */
    private String etag;
    /** FileStatus 枚举名称，以字符串形式保存。 */
    private String status;
    /** 失败业务码；正常记录为空，用于排查上传失败或补偿失败。 */
    private String errorCode;
}
