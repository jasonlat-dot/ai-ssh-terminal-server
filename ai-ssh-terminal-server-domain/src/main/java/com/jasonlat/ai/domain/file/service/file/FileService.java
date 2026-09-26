package com.jasonlat.ai.domain.file.service.file;

import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.domain.file.model.valobj.*;
import com.jasonlat.ai.domain.file.service.IFileService;
import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.service.storage.resolver.IObjectStorageResolver;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** 在应用层选定的存储上执行上传规则：准入检查、元数据落库、对象写入、签名和失败补偿。 */
@Slf4j
@Service
public class FileService implements IFileService {
    /** 保存文件位置与上传状态，便于后续业务引用和故障排查。 */
    private final IFileAssetRepository repository;
    /** app 装配的不可变上传规则，运行过程中不会被外部配置对象修改。 */
    private final FileUploadPolicy policy;
    /** 当前实例的上传并发许可，限制向对象存储传输时的缓冲区占用。 */
    private final Semaphore uploadSlots;
    /** 存储策略解析 */
    private final IObjectStorageResolver storageResolver;

    public FileService(IFileAssetRepository repository, FileUploadPolicy policy, IObjectStorageResolver storageResolver) {
        this.repository = repository;
        this.policy = policy;
        this.uploadSlots = new Semaphore(policy.maxConcurrentUploads());
        this.storageResolver = storageResolver;
    }

    /** 同步执行上传；输入流由调用方关闭，方法无论成功或失败都释放并发许可。 */
    @Override
    public FileUploadResult upload(FileUploadCommand command, InputStream input) {

        IObjectStorageService storage = storageResolver.defaultStorage();
        Objects.requireNonNull(storage, "storage");

        String fileName = validate(command, input);
        // 不排队等待许可，容量已满时立即返回 FILE_UPLOAD_BUSY，避免请求长期堆积。
        if (!uploadSlots.tryAcquire()) {
            throw new AppException(ResponseCode.FILE_UPLOAD_BUSY);
        }
        try {
            return doUpload(storage, command, fileName, input);
        } finally {
            uploadSlots.release();
        }
    }

    /** 执行存储与数据库操作；两者不在同一事务中，因此失败后需要显式补偿。 */
    private FileUploadResult doUpload(IObjectStorageService storage, FileUploadCommand command, String fileName, InputStream input) {
        String fileId = UUID.randomUUID().toString();
        // 按 UTC 日期组织对象，随机 ID 避免同名覆盖，客户端文件名不参与路径拼接。
        String key = "uploads/" + LocalDate.now(ZoneOffset.UTC) + "/" + fileId;
        FileAssetEntity asset = FileAssetEntity.builder()
                .fileId(fileId)
                .ownerId(command.ownerId())
                .originalName(fileName)
                .contentType(normalizeContentType(command.contentType()))
                .size(command.size())
                .location(storage.newLocation(key))
                .status(FileStatus.UPLOADING)
                .build();
        try {
            // 先落元数据再上传。即使中途进程退出，仍可根据记录定位残留对象。
            repository.create(asset);
        } catch (RuntimeException e) {
            throw new AppException(ResponseCode.FILE_UPLOAD_FAILED.getCode(),
                    ResponseCode.FILE_UPLOAD_FAILED.getInfo(), e);
        }

        // 只有收到存储的成功返回才算确认；超时不代表存储端一定没有写入。
        boolean uploadConfirmed = false;
        try {
            UploadInputStream stream = new UploadInputStream(input, command.size());
            StoredObject stored = storage.put(asset.getLocation(), stream, command.size());
            uploadConfirmed = true;
            // 采用实际返回的版本号，后续补偿才能准确删除本次上传的对象版本。
            asset.setLocation(stored.location());
            asset.setEtag(stored.etag());
            // 防止调用方声明的大小与真实输入不一致；不能把截断文件当作成功。
            if (stream.count != command.size() || stream.read() != -1) {
                throw new AppException(ResponseCode.FILE_INVALID);
            }
            asset.setSha256(HexFormat.of().formatHex(stream.digest.digest()));

            // 下载链接仅放进响应；数据库保留稳定对象位置，避免持久化已经过期的 URL。
            Instant expiresAt = Instant.now().plus(policy.downloadUrlTtl());
            URI downloadUrl = storage.createDownloadUrl(asset.getLocation(), fileName, policy.downloadUrlTtl());
            asset.setStatus(FileStatus.UPLOADED);

            repository.update(asset);
            log.info("文件上传完成 fileId={} storageId={} size={}", fileId, storage.storageId(), command.size());

            return new FileUploadResult(fileId, fileName, asset.getContentType(), command.size(),
                    asset.getSha256(), asset.getStatus().name(), downloadUrl.toString(), expiresAt);
        } catch (Exception failure) {
            // 对象存储与数据库不是同一事务。失败时删除可能已写入的对象，并持久化补偿结果。
            log.warn("文件上传失败 fileId={} storageId={} errorType={}",
                    fileId, storage.storageId(), failure.getClass().getSimpleName());
            compensate(storage, asset, failure, uploadConfirmed);
            if (failure instanceof AppException appException) {
                throw appException;
            }
            throw new AppException(ResponseCode.FILE_UPLOAD_FAILED.getCode(),
                    ResponseCode.FILE_UPLOAD_FAILED.getInfo(), failure);
        }
    }

    /** 尽力删除残留对象并记录结果；补偿失败不能覆盖原始上传错误。 */
    private void compensate(IObjectStorageService storage, FileAssetEntity asset, Exception failure, boolean uploadConfirmed) {
        // PUT 超时可能只是响应丢失，甚至与后续 DELETE 交错；没有确认结果时保留待核对状态。
        asset.setStatus(uploadConfirmed ? FileStatus.FAILED : FileStatus.CLEANUP_REQUIRED);
        asset.setErrorCode(failure instanceof AppException e
                ? e.getCode() : ResponseCode.FILE_UPLOAD_FAILED.getCode());
        try {
            storage.delete(asset.getLocation());
        } catch (Exception cleanupFailure) {
            asset.setStatus(FileStatus.CLEANUP_REQUIRED);
            log.error("上传失败且对象清理失败 fileId={} storageId={} errorType={}",
                    asset.getFileId(), asset.getLocation().storageId(),
                    cleanupFailure.getClass().getSimpleName());
        }
        try {
            repository.update(asset);
        } catch (RuntimeException updateFailure) {
            log.error("上传失败状态写入失败 fileId={} errorType={}",
                    asset.getFileId(), updateFailure.getClass().getSimpleName());
        }
    }

    /** 检查大小、文件名及扩展名，返回用于展示的文件名；不执行真实内容解析或扫描。 */
    private String validate(FileUploadCommand command, InputStream input) {
        if (command == null || input == null || command.size() <= 0
                || command.originalName() == null || command.originalName().isBlank()) {
            throw new AppException(ResponseCode.FILE_INVALID);
        }
        if (command.size() > policy.maxFileSizeBytes()) {
            throw new AppException(ResponseCode.FILE_TOO_LARGE);
        }
        // 兼容浏览器传来的 C:\\fakepath，文件名仅作展示，不参与 objectKey 拼接。
        String name = command.originalName().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new AppException(ResponseCode.FILE_INVALID);
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (policy.allowedExtensions().stream().noneMatch(extension::equalsIgnoreCase)) {
            throw new AppException(ResponseCode.FILE_TYPE_NOT_ALLOWED);
        }
        if (command.ownerId() != null && command.ownerId().length() > 128) {
            throw new AppException(ResponseCode.FILE_INVALID);
        }
        return name;
    }

    /** 将缺失或格式不合法的客户端 MIME 声明归一为二进制类型，仅用于元数据记录。 */
    private String normalizeContentType(String type) {
        if (type == null || type.length() > 127
                || !type.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /** 在 SDK 消费流时计算摘要和限制字节数，不复制完整文件到堆内存。 */
    private static final class UploadInputStream extends FilterInputStream {
        /** 本次请求声明的字节数，实际读取超过该值即终止。 */
        private final long limit;
        /** 随字节读取逐步更新的 SHA-256 计算器。 */
        private final MessageDigest digest;
        /** 已从底层流读取的字节数，用于完成后的长度一致性检查。 */
        private long count;

        private UploadInputStream(InputStream input, long limit) throws NoSuchAlgorithmException {
            super(input);
            this.limit = limit;
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                checkCount(1);
                digest.update((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            // 最多额外探测一个字节，以区分“恰好读完”和“实际内容超过声明大小”。
            int read = in.read(buffer, offset, (int) Math.min(length, limit - count + 1));
            if (read > 0) {
                checkCount(read);
                digest.update(buffer, offset, read);
            }
            return read;
        }

        private void checkCount(int length) throws IOException {
            count += length;
            if (count > limit) {
                throw new IOException("文件实际大小超过声明大小");
            }
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("上传流不支持重复读取");
        }

        @Override
        public long skip(long n) throws IOException {
            // 摘要必须覆盖全部字节，不允许绕过 read 直接跳过底层流。
            byte[] buffer = new byte[8192];
            long skipped = 0;
            while (skipped < n) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (read == -1) break;
                skipped += read;
            }
            return skipped;
        }
    }
}
