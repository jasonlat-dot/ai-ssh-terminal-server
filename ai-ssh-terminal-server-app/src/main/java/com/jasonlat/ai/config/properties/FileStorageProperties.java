package com.jasonlat.ai.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
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
    /** 本机文件系统配置组；适合后端随桌面客户端安装且不依赖外部对象服务的场景。 */
    private LocalFile local = new LocalFile();

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

    /** 绑定 ai.file.storage.local；文件内容只保存在运行后端的这台机器上。 */
    @Getter
    @Setter
    public static class LocalFile {
        /** 是否注册本地存储；需要同时把 default-id 指向本实例，新上传才会使用它。 */
        private boolean enabled;
        /** 稳定实例 ID，会持久化到 file_asset.storage_id，启用后不要随意修改。 */
        private String storageId = "local-main";
        /**
         * 附件根目录。默认位于当前用户目录，避免安装在 Program Files 时因目录只读而失败。
         * 客户端安装器也可以通过 LOCAL_FILE_STORAGE_ROOT 指向应用数据目录。
         */
        private String rootDirectory = Path.of(System.getProperty("user.home"),
                ".ai-ssh-terminal", "files").toString();
        /**
         * 可选的固定下载接口地址，不是磁盘目录。留空时根据当前上传请求动态生成；
         * 仅在反向代理未正确传递外部协议、域名或端口时需要手工配置。
         */
        private String publicBaseUrl;
        /**
         * HMAC 下载签名密钥。为空时启动时随机生成，后端重启会使旧临时链接提前失效；
         * 需要跨重启保持链接有效时，应通过环境变量配置固定随机密钥。
         */
        private String signingSecret;
    }
}
