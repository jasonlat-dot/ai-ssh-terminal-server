package com.jasonlat.ai.test.domain.file;

import com.jasonlat.ai.domain.file.model.valobj.LocalFileStorageSettings;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;
import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;
import com.jasonlat.ai.domain.file.service.storage.LocalFileIObjectStorageService;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证本地存储的原子写入、读取、临时下载验签及幂等删除。 */
class LocalFileStorageServiceTest {

    /** 每个测试独占的临时存储根目录，测试结束后由 JUnit 清理。 */
    @TempDir
    Path root;

    /** 覆盖写入、读取、签名下载、防篡改及幂等删除的完整本地存储流程。 */
    @Test
    void storesAndDownloadsWithoutExposingTheAbsoluteRoot() throws Exception {
        // 使用临时目录创建真实适配器，避免测试接触用户的正式附件目录。
        LocalFileIObjectStorageService storage = storage();
        byte[] content = "local attachment".getBytes(StandardCharsets.UTF_8);
        ObjectLocation location = storage.newLocation("uploads/2026-09-28/file-id");

        // 写入后通过存储读取接口核对完整内容，确认正式对象已经发布。
        StoredObject stored = storage.put(location, new ByteArrayInputStream(content), content.length);
        try (InputStream input = storage.openRead(stored.location())) {
            assertArrayEquals(content, input.readAllBytes());
        }

        // 下载 URL 不得暴露真实根目录，解析其签名参数后模拟 Controller 下载流程。
        URI url = storage.createDownloadUrl(stored.location(), "说明.txt", Duration.ofMinutes(5));
        assertFalse(url.isAbsolute());
        assertFalse(url.toString().contains(root.toString()));
        Map<String, String> query = query(url);
        try (TemporaryDownload download = open(storage, query)) {
            assertEquals("说明.txt", download.fileName());
            assertEquals(content.length, download.size());
            assertArrayEquals(content, download.input().readAllBytes());
        }

        // 文件名参与签名，修改文件名后必须拒绝下载。
        assertEquals("FILE_DOWNLOAD_LINK_INVALID", assertThrows(AppException.class,
                () -> storage.openTemporaryDownload(query.get("objectKey"), "被篡改.txt",
                        Long.parseLong(query.get("expires")), query.get("signature"))).getCode());

        // 重复删除按成功处理；删除后再读取应返回统一存储异常。
        storage.delete(stored.location());
        storage.delete(stored.location());
        assertEquals("FILE_STORAGE_UNAVAILABLE", assertThrows(AppException.class,
                () -> storage.openRead(stored.location())).getCode());
    }

    /** 创建使用测试临时目录和固定签名密钥的本地存储实例。 */
    private LocalFileIObjectStorageService storage() {
        return new LocalFileIObjectStorageService(new LocalFileStorageSettings(
                true,
                "local-main",
                root.toString(),
                "",
                "0123456789abcdef0123456789abcdef"));
    }

    /** 使用 URL 中解析出的参数调用本地临时下载接口。 */
    private TemporaryDownload open(LocalFileIObjectStorageService storage, Map<String, String> query) {
        return storage.openTemporaryDownload(
                query.get("objectKey"),
                query.get("fileName"),
                Long.parseLong(query.get("expires")),
                query.get("signature"));
    }

    /** 把临时下载 URL 的查询字符串解析成测试使用的键值表。 */
    private Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
