package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.domain.file.model.valobj.*;
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
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class FileService implements IFileService {
    private final ObjectStorageResolver storageResolver;
    private final IFileAssetRepository repository;
    private final FileUploadPolicy policy;
    private final Semaphore uploadSlots;

    public FileService(ObjectStorageResolver storageResolver, IFileAssetRepository repository,
                       FileUploadPolicy policy) {
        this.storageResolver = storageResolver;
        this.repository = repository;
        this.policy = policy;
        this.uploadSlots = new Semaphore(policy.maxConcurrentUploads());
    }

    @Override
    public FileUploadResult upload(FileUploadCommand command, InputStream input) {
        // 先检查存储配置，确保未启用存储时返回业务错误，不访问数据库或存储网络。
        ObjectStoragePort storage = storageResolver.defaultStorage();
        String fileName = validate(command, input);
        if (!uploadSlots.tryAcquire()) {
            throw new AppException(ResponseCode.FILE_UPLOAD_BUSY);
        }
        try {
            return doUpload(storage, command, fileName, input);
        } finally {
            uploadSlots.release();
        }
    }

    private FileUploadResult doUpload(ObjectStoragePort storage, FileUploadCommand command,
                                      String fileName, InputStream input) {
        String fileId = UUID.randomUUID().toString();
        String key = "uploads/" + LocalDate.now(ZoneOffset.UTC) + "/" + fileId;
        FileAssetEntity asset = FileAssetEntity.builder()
                .fileId(fileId).ownerId(command.ownerId()).originalName(fileName)
                .contentType(normalizeContentType(command.contentType())).size(command.size())
                .location(storage.newLocation(key)).status(FileStatus.UPLOADING).build();
        try {
            // 先落元数据再上传。即使中途进程退出，仍可根据记录定位残留对象。
            repository.create(asset);
        } catch (RuntimeException e) {
            throw new AppException(ResponseCode.FILE_UPLOAD_FAILED.getCode(),
                    ResponseCode.FILE_UPLOAD_FAILED.getInfo(), e);
        }

        boolean uploadConfirmed = false;
        try {
            UploadInputStream stream = new UploadInputStream(input, command.size());
            StoredObject stored = storage.put(asset.getLocation(), stream, command.size());
            uploadConfirmed = true;
            asset.setLocation(stored.location());
            asset.setEtag(stored.etag());
            // 防止调用方声明的大小与真实输入不一致；不能把截断文件当作成功。
            if (stream.count != command.size() || stream.read() != -1) {
                throw new AppException(ResponseCode.FILE_INVALID);
            }
            asset.setSha256(HexFormat.of().formatHex(stream.digest.digest()));

            Instant expiresAt = Instant.now().plus(policy.downloadUrlTtl());
            URI downloadUrl = storage.createDownloadUrl(
                    asset.getLocation(), fileName, policy.downloadUrlTtl());
            asset.setStatus(FileStatus.UPLOADED);
            repository.update(asset);
            log.info("文件上传完成 fileId={} storageId={} size={}",
                    fileId, storage.storageId(), command.size());
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

    private void compensate(ObjectStoragePort storage, FileAssetEntity asset, Exception failure,
                            boolean uploadConfirmed) {
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

    private String normalizeContentType(String type) {
        if (type == null || type.length() > 127
                || !type.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /** 在 SDK 消费流时计算摘要和限制字节数，不复制完整文件到堆内存。 */
    private static final class UploadInputStream extends FilterInputStream {
        private final long limit;
        private final MessageDigest digest;
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
