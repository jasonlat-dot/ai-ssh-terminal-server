package com.jasonlat.ai.trigger.http;

import com.jasonlat.ai.cases.ILocalFileDownloadCase;
import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 本机文件系统的临时下载入口。
 *
 * <p>该接口只接收本地存储实现签发的参数；业务层会验证过期时间和 HMAC 签名，
 * objectKey 始终在配置根目录中解析，不能由请求参数访问任意本机文件。
 */
@RestController
@RequestMapping("/api/v1/files/local")
@CrossOrigin("*")
public class LocalFileDownloadController {
    /** 负责选择存储实例、验签并打开下载文件流的应用门面。 */
    private final ILocalFileDownloadCase downloadCase;

    /** 注入本地附件下载门面。 */
    public LocalFileDownloadController(ILocalFileDownloadCase downloadCase) {
        this.downloadCase = downloadCase;
    }

    /** 验签后以附件形式流式返回文件，不把整个文件一次性加载到 JVM 堆内存。 */
    @GetMapping
    public ResponseEntity<InputStreamResource> download(
            @RequestParam String storageId,
            @RequestParam String objectKey,
            @RequestParam String fileName,
            @RequestParam long expires,
            @RequestParam String signature) {
        // 应用门面先完成 storageId 路由、有效期检查、HMAC 验签和安全磁盘路径解析。
        TemporaryDownload download = downloadCase.open(
                storageId, objectKey, fileName, expires, signature);

        // Content-Disposition 使用 UTF-8 文件名；响应强制下载，不让浏览器以内联页面执行附件内容。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        // InputStreamResource 直接把磁盘流写入 HTTP 响应，避免把完整文件加载进 JVM 内存。
        // no-store 防止带签名的临时响应被浏览器或中间代理长期缓存。
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new InputStreamResource(download.input()));
    }
}
