package com.jasonlat.ai.domain.file.model.valobj;

import java.time.Duration;

/**
 * MinIO 适配器使用的不可变参数。
 * 不在构造时校验完整性：未配置存储仍允许启动，由上传请求返回明确业务错误。
 *
 * @param enabled 是否启用该存储实例
 * @param storageId 存储实例的稳定标识，用于选择实现及持久化定位
 * @param endpoint 后端访问的对象 API 地址
 * @param publicEndpoint 可选的外部对象 API 地址，用于给浏览器生成有效签名
 * @param accessKey MinIO 应用账号标识
 * @param secretKey 请求签名密钥，不输出到日志或前端
 * @param bucket 预先创建的私有桶名称
 * @param region 请求签名使用的区域
 * @param connectTimeout 建立网络连接的等待上限
 * @param readTimeout 网络读取操作的等待上限
 * @param writeTimeout 网络写入操作的等待上限
 * @param callTimeout 单次 HTTP 调用总时限，不等同于多分片上传的总时限
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
