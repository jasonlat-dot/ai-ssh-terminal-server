package com.jasonlat.ai.domain.file.service.storage;

import com.jasonlat.ai.domain.file.model.valobj.MinioStorageSettings;
import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** MinIO 存储适配器，封装 SDK 的上传、签名和删除操作，对上只暴露领域类型。 */
@Component
public class MinioIObjectStorageService implements IObjectStorageService {
    /** 固定分片缓冲，结合上传并发限制控制内存，不随文件总大小分配 byte[]。 */
    private static final long PART_SIZE = 5L * 1024 * 1024;
    /** app 装配的连接参数快照，包含内部端点和可选的外部签名端点。 */
    private final MinioStorageSettings settings;
    /** 首次使用时创建并复用；volatile 保证其他上传线程能看到完整初始化的客户端。 */
    private volatile Clients clients;

    public MinioIObjectStorageService(MinioStorageSettings settings) {
        this.settings = settings;
    }

    @Override
    public String storageId() {
        return settings.storageId();
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public void validateConfiguration() {
        if (!enabled()) {
            throw new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        // 不在构造方法中创建 SDK 客户端，否则空 endpoint 会令整个应用启动失败。
        if (blank(settings.endpoint()) || blank(settings.accessKey())
                || blank(settings.secretKey()) || blank(settings.bucket())
                || blank(settings.region()) || blank(settings.storageId())
                || settings.storageId().length() > 64 || settings.region().length() > 128
                || !validEndpoint(settings.endpoint())
                || (!blank(settings.publicEndpoint()) && !validEndpoint(settings.publicEndpoint()))
                || !settings.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || !validTimeout(settings.connectTimeout()) || !validTimeout(settings.readTimeout())
                || !validTimeout(settings.writeTimeout()) || !validTimeout(settings.callTimeout())) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }
        clients();
    }

    @Override
    public ObjectLocation newLocation(String objectKey) {
        return new ObjectLocation(storageId(), settings.bucket(), objectKey, null);
    }

    @Override
    public StoredObject put(ObjectLocation location, InputStream input, long size) {
        validateConfiguration();
        try {
            // 第一阶段仅存储文件：不把客户端声明的 MIME 当作可信内容，默认强制下载。
            ObjectWriteResponse response = clients().writer().putObject(PutObjectArgs.builder()
                    .bucket(location.bucket()).object(location.objectKey())
                    .region(settings.region())
                    .stream(input, size, PART_SIZE)
                    .contentType("application/octet-stream")
                    .headers(Map.of("Content-Disposition", "attachment"))
                    .build());
            return new StoredObject(new ObjectLocation(storageId(), location.bucket(),
                    location.objectKey(), response.versionId()), response.etag());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    @Override
    public URI createDownloadUrl(ObjectLocation location, String fileName, Duration ttl) {
        validateConfiguration();
        try {
            // 编码中文、空格等字符，放入 Content-Disposition 的 filename* 下载文件名参数。
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            GetPresignedObjectUrlArgs.Builder args = GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(location.bucket()).object(location.objectKey())
                    .region(settings.region()).expiry(Math.toIntExact(ttl.toSeconds()))
                    .extraQueryParams(Map.of(
                            "response-content-type", "application/octet-stream",
                            "response-content-disposition", "attachment; filename=\"download\"; filename*=UTF-8''" + encodedName));
            if (location.versionId() != null) args.versionId(location.versionId());
            return URI.create(clients().signer().getPresignedObjectUrl(args.build()));
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    @Override
    public void delete(ObjectLocation location) {
        validateConfiguration();
        try {
            RemoveObjectArgs.Builder args = RemoveObjectArgs.builder()
                    .bucket(location.bucket()).object(location.objectKey()).region(settings.region());
            if (location.versionId() != null) args.versionId(location.versionId());
            clients().writer().removeObject(args.build());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    /** 双重检查创建客户端；锁只保护初始化，不包住文件传输过程。 */
    private Clients clients() {
        Clients current = clients;
        if (current != null) return current;
        synchronized (this) {
            if (clients == null) {
                OkHttpClient http = null;
                try {
                    http = new OkHttpClient.Builder()
                            .connectTimeout(settings.connectTimeout())
                            .readTimeout(settings.readTimeout())
                            .writeTimeout(settings.writeTimeout())
                            .callTimeout(settings.callTimeout())
                            .build();
                    MinioClient writer = newClient(settings.endpoint(), http);
                    // 外部地址参与签名计算，不能先按内部地址签名再替换返回 URL 的域名。
                    MinioClient signer = blank(settings.publicEndpoint())
                            ? writer : newClient(settings.publicEndpoint(), http);
                    clients = new Clients(writer, signer, http);
                } catch (RuntimeException e) {
                    if (http != null) {
                        http.dispatcher().executorService().shutdown();
                        http.connectionPool().evictAll();
                    }
                    throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID.getCode(),
                            ResponseCode.FILE_STORAGE_CONFIG_INVALID.getInfo(), e);
                }
            }
            return clients;
        }
    }

    private MinioClient newClient(String endpoint, OkHttpClient http) {
        return MinioClient.builder().endpoint(endpoint)
                .credentials(settings.accessKey(), settings.secretKey())
                .region(settings.region()).httpClient(http).build();
    }

    private AppException unavailable(Exception cause) {
        // 不把存储 SDK 错误原文返回前端，避免泄露内部地址或签名。
        return new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE.getCode(),
                ResponseCode.FILE_STORAGE_UNAVAILABLE.getInfo(), cause);
    }

    private boolean validEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean validTimeout(Duration value) {
        return value != null && value.toMillis() > 0 && value.toMillis() <= Integer.MAX_VALUE;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 应用关闭时释放本适配器创建的 HTTP 线程池及空闲连接。 */
    @PreDestroy
    public void close() {
        Clients current = clients;
        if (current != null) {
            current.http().dispatcher().executorService().shutdown();
            current.http().connectionPool().evictAll();
        }
    }

    private record Clients(MinioClient writer, MinioClient signer, OkHttpClient http) {
    }
}
