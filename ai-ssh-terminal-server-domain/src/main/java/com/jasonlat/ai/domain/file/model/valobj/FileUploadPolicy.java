package com.jasonlat.ai.domain.file.model.valobj;

import java.time.Duration;
import java.util.Set;

/**
 * 文件上传规则快照，由 app 配置转换得到，不依赖 Spring 配置类型。
 *
 * @param maxFileSizeBytes 单个文件允许的最大字节数
 * @param maxConcurrentUploads 当前后端实例允许同时执行的上传数，不是跨实例的用户配额
 * @param downloadUrlTtl 签名下载地址有效时长，当前限制为 1 秒至 7 天
 * @param allowedExtensions 允许的文件扩展名集合，不带点；只作格式准入，不替代内容检查
 */
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
