package com.jasonlat.ai.domain.file.model.valobj;

import java.time.Instant;

/**
 * 领域服务在文件和元数据保存成功后返回的结果。
 *
 * @param fileId 文件的稳定业务标识
 * @param fileName 去除客户端路径后的展示文件名
 * @param contentType 规范化后的客户端 MIME 类型，不代表内容检测结果
 * @param size 文件大小，单位为字节
 * @param sha256 后端计算的文件 SHA-256 十六进制摘要
 * @param status 当前为 UPLOADED，仅表示已存储，不表示已解析或通过安全扫描
 * @param downloadUrl 有有效期的签名下载地址，不作为永久文件标识
 * @param urlExpiresAt 根据签名有效时长计算的到期时间，供调用方判断链接是否过期
 */
public record FileUploadResult(String fileId, String fileName, String contentType,
                               long size, String sha256, String status,
                               String downloadUrl, Instant urlExpiresAt) {
}
