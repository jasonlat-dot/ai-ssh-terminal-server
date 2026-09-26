package com.jasonlat.ai.trigger.api.dto.file;

import java.time.Instant;

/** 下载 URL 会过期，fileId 才是文件的稳定标识。 */
public record FileUploadResponseDTO(String fileId, String fileName, String contentType,
                                    long size, String sha256, String status,
                                    String downloadUrl, Instant urlExpiresAt) {
}
