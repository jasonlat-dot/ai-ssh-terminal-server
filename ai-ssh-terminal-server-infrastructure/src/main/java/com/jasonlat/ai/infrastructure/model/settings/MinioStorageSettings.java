package com.jasonlat.ai.infrastructure.model.settings;

import java.time.Duration;

/**
 * MinIO 适配器使用的不可变参数。
 * 不在构造时校验完整性：未配置存储仍允许启动，由上传请求返回明确业务错误。
 */
public record MinioStorageSettings(
        boolean enabled,
        String storageId,
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        Duration connectTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        Duration callTimeout) {

    @Override
    public String toString() {
        return "MinioStorageSettings[storageId=" + storageId + ", enabled=" + enabled + "]";
    }
}
