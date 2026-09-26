package com.jasonlat.ai.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 不使用 @Data，避免生成包含密钥的 toString；配置完整性在上传请求中检查。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai.file.storage")
public class FileStorageProperties {
    /** 新上传文件使用的存储实例 ID，必须对应一个已启用的存储实现。 */
    private String defaultId = "minio-main";
    /** MinIO 配置组；默认存在但未启用，空配置不应阻止应用启动。 */
    private Minio minio = new Minio();

    /** 绑定 ai.file.storage.minio；仅承载配置，由 app 转换为 MinioStorageSettings。 */
    @Getter
    @Setter
    public static class Minio {
        /** 是否参与存储注册；默认关闭，启用后才允许通过该实例上传。 */
        private boolean enabled;
        /** 实例的稳定标识，会写入文件元数据，不能随意改名而不处理旧记录。 */
        private String storageId = "minio-main";
        /** 后端访问的 MinIO 对象 API 地址，包含协议和端口，不是管理控制台地址。 */
        private String endpoint;
        /** 可选：给浏览器签名的外部地址。必须在签名前使用，不能事后替换 URL 域名。 */
        private String publicEndpoint;
        /** 应用访问 MinIO 的账号标识，不返回给前端。 */
        private String accessKey;
        /** 与账号配套的签名密钥，不应写入日志或源码中的固定值。 */
        private String secretKey;
        /** 预先创建的私有桶名称，当前实现不会自动建桶或修改访问策略。 */
        private String bucket;
        /** 桶所在区域，用于请求签名，应与存储端设置一致。 */
        private String region = "us-east-1";
        /** 建立存储网络连接的等待上限。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 网络读取操作的等待上限，不是整个文件上传的总时限。 */
        private Duration readTimeout = Duration.ofSeconds(30);
        /** 网络写入操作的等待上限。 */
        private Duration writeTimeout = Duration.ofSeconds(60);
        /** 单次 HTTP 调用的总时限；分片上传可能包含多次 HTTP 调用。 */
        private Duration callTimeout = Duration.ofMinutes(2);
    }
}
