package com.jasonlat.ai.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 不使用 @Data，避免生成包含密钥的 toString；配置完整性在上传请求中检查。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.file.storage")
public class FileStorageProperties {
    private String defaultId = "minio-main";
    private Minio minio = new Minio();

    @Getter
    @Setter
    public static class Minio {
        private boolean enabled;
        private String storageId = "minio-main";
        private String endpoint;
        /** 可选：给浏览器签名的外部地址。必须在签名前使用，不能事后替换 URL 域名。 */
        private String publicEndpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region = "us-east-1";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Duration writeTimeout = Duration.ofSeconds(60);
        private Duration callTimeout = Duration.ofMinutes(2);
    }
}
