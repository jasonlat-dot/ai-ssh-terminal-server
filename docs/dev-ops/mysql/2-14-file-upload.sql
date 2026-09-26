-- 在应用实际使用的 MySQL 数据库中执行。本脚本只新增表，不删除或修改现有表。
CREATE TABLE IF NOT EXISTS file_asset (
    file_id varchar(36) NOT NULL COMMENT '服务端生成的稳定文件ID',
    owner_id varchar(128) DEFAULT NULL COMMENT '认证Principal；当前未接入登录时为空',
    original_name varchar(255) NOT NULL COMMENT '展示文件名，不作为存储路径',
    content_type varchar(127) NOT NULL COMMENT '客户端声明类型，不代表内容已校验',
    size_bytes bigint NOT NULL COMMENT '文件字节数',
    sha256 char(64) DEFAULT NULL COMMENT '服务端读取流时计算的SHA-256',
    storage_id varchar(64) NOT NULL COMMENT '具体存储实例，例如minio-main',
    bucket varchar(63) NOT NULL,
    object_key varchar(512) NOT NULL,
    object_version varchar(255) DEFAULT NULL,
    etag varchar(255) DEFAULT NULL,
    status varchar(32) NOT NULL COMMENT 'UPLOADING/UPLOADED/FAILED/CLEANUP_REQUIRED',
    error_code varchar(64) DEFAULT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_storage_object (storage_id, bucket, object_key),
    KEY idx_file_status_time (status, updated_at),
    KEY idx_file_owner_time (owner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立文件上传元数据';
