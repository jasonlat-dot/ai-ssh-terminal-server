package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;

/**
 * 需要由本应用提供临时 HTTP 下载入口的存储扩展。
 *
 * <p>MinIO 自己生成预签名地址，不需要实现本接口；本地文件系统没有对象服务 HTTP
 * 端点，因此由 Controller 把已验签的文件流返回给客户端。
 */
public interface ITemporaryDownloadStorageService {

    /**
     * 校验临时链接并打开文件。
     *
     * @param objectKey 存储内对象路径，不是本机绝对路径
     * @param fileName 下载文件名，同时参与签名，禁止请求方签名后篡改
     * @param expiresAtEpochSecond 链接失效时间，Unix 秒
     * @param signature 存储实现签发的 URL-safe 签名
     */
    TemporaryDownload openTemporaryDownload(
            String objectKey,
            String fileName,
            long expiresAtEpochSecond,
            String signature);
}
