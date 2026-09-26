package com.jasonlat.ai.domain.file.model.valobj;

import java.time.Duration;
import java.util.Set;

/** 文件上传规则；使用字节数和 JDK 类型表达，不依赖 Spring DataSize 或 Properties。 */
public record FileUploadPolicy(
        long maxFileSizeBytes,
        int maxConcurrentUploads,
        Duration downloadUrlTtl,
        Set<String> allowedExtensions) {

    public FileUploadPolicy {
        if (maxFileSizeBytes <= 0 || maxConcurrentUploads <= 0
                || downloadUrlTtl == null || downloadUrlTtl.toSeconds() < 1
                || downloadUrlTtl.compareTo(Duration.ofDays(7)) > 0
                || allowedExtensions == null || allowedExtensions.isEmpty()
                || allowedExtensions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("文件上传大小、并发、有效期或扩展名规则不合法");
        }
        // 防止外部修改配置集合后影响已运行的领域服务。
        allowedExtensions = Set.copyOf(allowedExtensions);
    }
}
