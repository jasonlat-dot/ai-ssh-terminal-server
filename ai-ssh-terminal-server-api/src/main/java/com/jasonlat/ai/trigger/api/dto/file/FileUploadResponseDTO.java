package com.jasonlat.ai.trigger.api.dto.file;

import java.time.Instant;

/**
 * 上传成功后返回前端的文件信息；不暴露桶名称、存储凭据等内部配置。
 *
 * @param fileId 后端生成的文件 ID，后续业务应使用该标识引用文件
 * @param fileName 用于界面展示和下载命名的文件名，已去掉客户端路径
 * @param contentType 规范化后的客户端声明 MIME 类型，不能据此认定文件安全
 * @param size 文件大小，单位为字节，前端可转换成 KB 或 MB 展示
 * @param sha256 文件内容的 SHA-256 十六进制摘要
 * @param status 上传状态，当前成功返回 UPLOADED，不表示完成内容解析
 * @param downloadUrl 临时签名下载地址；应直接使用，不能自行替换域名、路径或参数
 * @param urlExpiresAt 下载链接预计到期时间，序列化为 UTC 时间字符串
 */
public record FileUploadResponseDTO(String fileId, String fileName, String contentType,
                                    long size, String sha256, String status,
                                    String downloadUrl, Instant urlExpiresAt) {
}
