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
    private DataSize maxFileSize = DataSize.ofMegabytes(20);
    /** 单实例限制，用于控制 SDK 分片缓冲占用；不是跨实例的用户配额。 */
    private int maxConcurrentUploads = 4;
    private Duration downloadUrlTtl = Duration.ofMinutes(15);
    private Set<String> allowedExtensions = Set.of(
            "png", "jpg", "jpeg", "webp", "pdf", "txt", "log", "csv",
            "json", "yaml", "yml", "md", "docx", "xlsx");

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
