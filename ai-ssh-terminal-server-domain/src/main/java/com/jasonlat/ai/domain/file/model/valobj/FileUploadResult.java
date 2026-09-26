package com.jasonlat.ai.domain.file.model.valobj;

import java.time.Instant;

/** UPLOADED 只表示文件已经存储，不表示通过了病毒扫描或可以作为模型输入。 */
public record FileUploadResult(String fileId, String fileName, String contentType,
                               long size, String sha256, String status,
                               String downloadUrl, Instant urlExpiresAt) {
}
