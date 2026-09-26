package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Set;

/** 绑定上传配置；由 app 装配为领域使用的 FileUploadPolicy。 */
@Data
@ConfigurationProperties(prefix = "ai.file.upload")
public class FileUploadProperties implements InitializingBean {
    /** 单文件上限，支持 20MB 等配置写法；装配时转换为领域使用的字节数。 */
    private DataSize maxFileSize = DataSize.ofMegabytes(20);
    /** 单实例限制，用于控制 SDK 分片缓冲占用；不是跨实例的用户配额。 */
    private int maxConcurrentUploads = 4;
    /** 上传响应中临时下载链接的有效时长，不是文件在存储中的保留时长。 */
    private Duration downloadUrlTtl = Duration.ofMinutes(15);
    /** 可接收的文件扩展名，不带点；实际文件内容检测属于后续处理能力。 */
    private Set<String> allowedExtensions = Set.of(
            "png", "jpg", "jpeg", "webp", "pdf", "txt", "log", "csv",
            "json", "yaml", "yml", "md", "docx", "xlsx");

    /** 启动时检查上传规则，避免无效限额进入领域服务。存储连接配置另在请求时检查。 */
    @Override
    public void afterPropertiesSet() {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0 || maxConcurrentUploads <= 0
                || downloadUrlTtl == null || downloadUrlTtl.toSeconds() < 1
                || downloadUrlTtl.compareTo(Duration.ofDays(7)) > 0
                || allowedExtensions == null || allowedExtensions.isEmpty()) {
            throw new IllegalArgumentException("ai.file.upload 的大小、并发、有效期或扩展名配置不合法");
        }
    }
}
