package com.jasonlat.ai.domain.file.service.storage;

import com.jasonlat.ai.domain.file.model.valobj.LocalFileStorageSettings;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.model.valobj.StoredObject;
import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;
import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.service.ITemporaryDownloadStorageService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 本机文件系统存储适配器。
 *
 * <p>文件先写入同目录临时文件，完整写入后再原子移动到正式位置，防止进程中断留下
 * 看似成功的半文件。所有对象路径都必须位于配置根目录内，并在读取时再次检查真实路径，
 * 避免通过 {@code ..} 或符号链接访问根目录之外的文件。
 */
@Slf4j
@Component
public class LocalFileIObjectStorageService implements IObjectStorageService, ITemporaryDownloadStorageService {

    /** ObjectLocation 仍保留 bucket 字段；本地实现使用固定逻辑名称，不代表真实目录。 */
    private static final String LOCAL_BUCKET = "local-files";
    /** 临时下载地址使用的消息认证算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 文件复制缓冲区大小，固定占用内存，不随附件大小增长。 */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** 应用启动阶段生成的本地存储配置快照。 */
    private final LocalFileStorageSettings settings;
    /** 空 signing-secret 时生成的进程级随机密钥，不写入磁盘或日志。 */
    private final byte[] signingKey;
    /** 首次使用时完成目录创建与真实路径解析，后续请求直接复用。 */
    private volatile Path storageRoot;

    /** 保存配置，并准备下载链接使用的进程级签名密钥。 */
    public LocalFileIObjectStorageService(LocalFileStorageSettings settings) {
        this.settings = settings;
        this.signingKey = createSigningKey(settings.signingSecret());
    }

    /** 返回写入文件元数据的稳定存储实例 ID。 */
    @Override
    public String storageId() {
        return settings.storageId();
    }

    /** 返回本地存储是否参与当前应用的存储注册。 */
    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * 检查实例 ID、根目录和下载入口，并确保根目录可以创建。
     * 该方法仅在本实例被选中时调用，关闭本地存储不会创建任何目录。
     */
    @Override
    public void validateConfiguration() {
        // 未启用时返回统一业务错误，不能因为组件已经被 Spring 创建就允许使用。
        if (!enabled()) {
            throw new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        // 在第一次真实请求时集中校验配置，避免未使用文件功能时阻止整个后端启动。
        if (blank(settings.storageId()) || settings.storageId().length() > 64
                || blank(settings.rootDirectory())
                || (!blank(settings.publicBaseUrl()) && invalidPublicBaseUrl(settings.publicBaseUrl()))
                || (!blank(settings.signingSecret()) && settings.signingSecret().length() < 32)) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }
        // 初始化并验证存储根目录确实可以创建和解析。
        root();
    }

    /** 使用领域服务生成的随机 objectKey，不把客户端文件名拼进本地路径。 */
    @Override
    public ObjectLocation newLocation(String objectKey) {
        // objectKey 来自领域服务，但存储边界仍独立校验，防止其他调用方绕过上传流程。
        validateObjectKey(objectKey);
        return new ObjectLocation(storageId(), LOCAL_BUCKET, objectKey, null);
    }

    /**
     * 流式写入本机磁盘，并在写入字节数与声明不一致时删除临时文件。
     * 正式对象键由 UUID 构成，若目标已存在则拒绝覆盖，避免异常情况下破坏旧附件。
     */
    @Override
    public StoredObject put(ObjectLocation location, InputStream input, long size) {
        // 写入前同时校验实例配置和持久化位置，不能接受其他存储实例生成的位置。
        validateConfiguration();
        validateLocation(location);
        if (input == null || size < 0) {
            throw new AppException(ResponseCode.FILE_INVALID);
        }

        // 临时文件与目标文件放在同一目录，后续原子移动不会跨磁盘或跨文件系统。
        Path target = resolveForWrite(location.objectKey());
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".uploading");
        try {
            // 逐级创建并检查父目录，拒绝中间目录被替换成符号链接。
            prepareParent(target.getParent());

            // 流式复制并统计实际字节数；临时文件关闭后才进入正式提交阶段。
            long written;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                written = copy(input, output);
            }
            if (written != size) {
                throw new IOException("本地存储写入字节数与声明不一致");
            }

            // 完整写入后再发布正式文件，业务层只会看到完整对象或完全看不到对象。
            moveIntoPlace(temporary, target);
            log.info("本地附件写入完成 storageId={} objectKey={} size={}", storageId(), location.objectKey(), size);
            return new StoredObject(new ObjectLocation(storageId(), LOCAL_BUCKET, location.objectKey(), null), null);
        } catch (IOException exception) {
            // 任一步骤失败都尽力删除临时文件，再转换成统一的存储不可用错误。
            deleteQuietly(temporary);
            throw unavailable(exception);
        }
    }

    /** 打开持久化对象；返回流由上层业务或 HTTP 响应关闭。 */
    @Override
    public InputStream openRead(ObjectLocation location) {
        // 读取聊天附件时同样验证 storageId、逻辑桶和真实磁盘边界。
        validateConfiguration();
        validateLocation(location);
        try {
            return Files.newInputStream(resolveExisting(location.objectKey()));
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 生成带过期时间和 HMAC 签名的本机 HTTP 下载地址。
     * 地址只包含逻辑 objectKey，不包含附件根目录或任何本机绝对路径。
     */
    @Override
    public URI createDownloadUrl(ObjectLocation location, String fileName, Duration ttl) {
        // 下载链接只能针对已经属于当前实例的对象位置生成。
        validateConfiguration();
        validateLocation(location);
        if (blank(fileName) || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }

        // objectKey、下载名和失效时间共同参与签名，任一参数被修改都会导致验签失败。
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        String signature = sign(location.objectKey(), fileName, expiresAt);
        // 固定地址为空时返回相对于当前上传接口的地址，Controller 会使用本次请求的
        // 协议、主机、端口和部署前缀补全；配置固定地址时则直接返回该外部地址。
        String downloadBaseUrl = blank(settings.publicBaseUrl())
                ? "files/local"
                : settings.publicBaseUrl();
        // 所有查询参数都单独编码；URL 中只包含逻辑对象键，不包含真实根目录。
        String url = downloadBaseUrl + "?"
                + "storageId=" + encode(storageId())
                + "&objectKey=" + encode(location.objectKey())
                + "&fileName=" + encode(fileName)
                + "&expires=" + expiresAt
                + "&signature=" + encode(signature);
        return URI.create(url);
    }

    /** 删除不存在的对象按成功处理，满足上传失败补偿的幂等要求。 */
    @Override
    public void delete(ObjectLocation location) {
        // 补偿删除必须校验原始存储位置，避免删除其他实例或其他逻辑桶中的文件。
        validateConfiguration();
        validateLocation(location);
        try {
            Path target = resolveForWrite(location.objectKey());
            if (Files.exists(target)) {
                // 对已存在文件解析真实路径，阻止外部替换成指向根目录之外的符号链接。
                ensureInsideRoot(target.toRealPath());
                Files.deleteIfExists(target);
                log.info("本地附件删除完成 storageId={} objectKey={}", storageId(), location.objectKey());
            }
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    /** 下载 Controller 调用此方法完成过期校验、恒定时间验签和安全路径解析。 */
    @Override
    public TemporaryDownload openTemporaryDownload(
            String objectKey,
            String fileName,
            long expiresAtEpochSecond,
            String signature) {
        validateConfiguration();
        // 先检查有效期和必填参数，避免无效请求继续访问磁盘。
        if (expiresAtEpochSecond < Instant.now().getEpochSecond()) {
            throw new AppException(ResponseCode.FILE_DOWNLOAD_LINK_EXPIRED);
        }
        if (blank(objectKey) || blank(fileName) || blank(signature)) {
            throw new AppException(ResponseCode.FILE_DOWNLOAD_LINK_INVALID);
        }

        // 使用恒定时间比较签名，降低通过响应时间逐字节猜测正确签名的风险。
        String expected = sign(objectKey, fileName, expiresAtEpochSecond);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new AppException(ResponseCode.FILE_DOWNLOAD_LINK_INVALID);
        }

        try {
            // 验签通过后才解析真实文件并打开流，Controller 负责随 HTTP 响应关闭该流。
            Path target = resolveExisting(objectKey);
            return new TemporaryDownload(Files.newInputStream(target), Files.size(target), fileName);
        } catch (IOException exception) {
            throw new AppException(ResponseCode.FILE_DOWNLOAD_NOT_FOUND.getCode(),
                    ResponseCode.FILE_DOWNLOAD_NOT_FOUND.getInfo(), exception);
        }
    }

    /** 延迟创建并缓存本地存储根目录的真实绝对路径。 */
    private Path root() {
        // 无锁快速路径供后续上传和读取复用已经完成校验的根目录。
        Path current = storageRoot;
        if (current != null) {
            return current;
        }
        // 首次初始化加锁，避免并发上传重复创建目录或看到未完成初始化的路径。
        synchronized (this) {
            if (storageRoot == null) {
                try {
                    // toRealPath 会解析符号链接，后续所有边界判断都以同一个真实根目录为准。
                    Path configured = Path.of(settings.rootDirectory()).toAbsolutePath().normalize();
                    Files.createDirectories(configured);
                    storageRoot = configured.toRealPath();
                    log.info("本地附件存储已就绪 storageId={} root={}", storageId(), storageRoot);
                } catch (IOException | InvalidPathException exception) {
                    throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID.getCode(),
                            ResponseCode.FILE_STORAGE_CONFIG_INVALID.getInfo(), exception);
                }
            }
            return storageRoot;
        }
    }

    /** 把逻辑对象键转换为根目录内的待写入路径。 */
    private Path resolveForWrite(String objectKey) {
        validateObjectKey(objectKey);
        // objectKey 统一使用正斜杠保存，此处按当前操作系统转换路径分隔符。
        Path target = root().resolve(objectKey.replace('/', java.io.File.separatorChar)).normalize();
        ensureInsideRoot(target);
        return target;
    }

    /** 解析一个已存在的普通文件，并再次检查其真实路径边界。 */
    private Path resolveExisting(String objectKey) throws IOException {
        // toRealPath 会跟随实际文件系统状态，可发现不存在的文件和符号链接越界。
        Path target = resolveForWrite(objectKey).toRealPath();
        ensureInsideRoot(target);
        if (!Files.isRegularFile(target)) {
            throw new IOException("本地附件不是普通文件");
        }
        return target;
    }

    /**
     * 从根目录逐级创建父目录；已有路径如果是符号链接则拒绝继续。
     * 不能直接 createDirectories，因为外部篡改的中间符号链接可能把创建操作引向根目录之外。
     */
    private void prepareParent(Path parent) throws IOException {
        Path current = root();
        Path relative = current.relativize(parent);
        // 按层检查再创建，任何已有的符号链接或普通文件都会中止上传。
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("本地附件父路径不是普通目录");
                }
            } else {
                Files.createDirectory(current);
            }
        }
        ensureInsideRoot(parent.toRealPath());
    }

    /** 校验对象位置确实由当前本地存储实例生成。 */
    private void validateLocation(ObjectLocation location) {
        // 本地实现没有对象版本号，storageId 和逻辑桶也必须精确匹配当前实例。
        if (location == null || !storageId().equals(location.storageId())
                || !LOCAL_BUCKET.equals(location.bucket()) || location.versionId() != null) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }
        validateObjectKey(location.objectKey());
    }

    /** 校验对象键是安全的相对路径，不允许绝对路径、反斜杠或父目录跳转。 */
    private void validateObjectKey(String objectKey) {
        // 先排除不同操作系统下常见的绝对路径和控制字符表示。
        if (blank(objectKey) || objectKey.startsWith("/") || objectKey.startsWith("\\")
                || objectKey.contains("\\") || objectKey.indexOf('\0') >= 0) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }
        try {
            // 规范化后再次检查，拦截 uploads/../../secret 等隐藏的父目录跳转。
            Path relative = Path.of(objectKey).normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() == 0) {
                throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
            }
        } catch (InvalidPathException exception) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID.getCode(),
                    ResponseCode.FILE_STORAGE_CONFIG_INVALID.getInfo(), exception);
        }
    }

    /** 确认规范化路径仍位于已解析的存储根目录中。 */
    private void ensureInsideRoot(Path path) {
        if (!path.normalize().startsWith(root())) {
            throw new AppException(ResponseCode.FILE_STORAGE_CONFIG_INVALID);
        }
    }

    /** 使用固定大小缓冲区复制输入流，并返回实际复制字节数。 */
    private long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read;
        // 循环读取而不是 readAllBytes，避免大附件按总大小占用 JVM 堆内存。
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        output.flush();
        return total;
    }

    /** 优先原子发布文件，文件系统不支持时退回同目录普通移动。 */
    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            // 同目录原子移动可保证读取方不会观察到只写了一部分的正式文件。
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // 某些文件系统不提供 ATOMIC_MOVE；同目录普通移动仍不会复制文件内容。
            Files.move(source, target);
        }
    }

    /** 对下载地址的稳定字段计算 URL-safe HMAC 签名。 */
    private String sign(String objectKey, String fileName, long expiresAt) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            // 使用换行分隔字段，避免不同字段组合连接后产生相同签名原文。
            String payload = storageId() + "\n" + objectKey + "\n" + fileName + "\n" + expiresAt;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前 JDK 不支持本地下载签名算法", exception);
        }
    }

    /** 使用固定配置密钥，未配置时生成只在本次进程生命周期内有效的随机密钥。 */
    private byte[] createSigningKey(String configuredSecret) {
        if (!blank(configuredSecret)) {
            // 配置完整性会在实例真正使用时检查，此处只转换且不输出密钥内容。
            return configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        // 随机密钥免去单机默认部署的额外配置，代价是重启后旧临时链接失效。
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        return generated;
    }

    /** 判断下载入口是否为不带凭据、查询参数和锚点的 HTTP(S) 地址。 */
    private boolean invalidPublicBaseUrl(String value) {
        if (blank(value)) {
            return true;
        }
        try {
            // 禁止 user-info、query 和 fragment，避免签名参数与管理员预置参数混合。
            URI uri = URI.create(value);
            return (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null;
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    /** 按 UTF-8 编码单个下载查询参数。 */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 上传失败时尽力清理临时文件，清理失败只记日志，不覆盖原始异常。 */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("本地附件临时文件清理失败 path={}", path, exception);
        }
    }

    /** 把底层磁盘异常转换成统一的文件存储业务异常。 */
    private AppException unavailable(Exception cause) {
        return new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE.getCode(),
                ResponseCode.FILE_STORAGE_UNAVAILABLE.getInfo(), cause);
    }

    /** 判断字符串配置是否为空或只包含空白字符。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
