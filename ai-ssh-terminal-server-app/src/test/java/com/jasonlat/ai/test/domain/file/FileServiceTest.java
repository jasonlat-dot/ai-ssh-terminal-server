package com.jasonlat.ai.test.domain.file;

import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.domain.file.model.valobj.*;
import com.jasonlat.ai.domain.file.service.file.FileService;
import com.jasonlat.ai.domain.file.service.storage.resolver.DefaultIObjectStorageResolver;
import com.jasonlat.ai.domain.file.service.storage.resolver.IObjectStorageResolver;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileServiceTest {
    private final IObjectStorageService storage = mock(IObjectStorageService.class);
    private final IFileAssetRepository repository = mock(IFileAssetRepository.class);
    private final IObjectStorageResolver resolver = mock(DefaultIObjectStorageResolver.class);
    private final FileUploadPolicy policy = new FileUploadPolicy(20L * 1024 * 1024, 4,
            Duration.ofMinutes(15), Set.of("txt"));
    private FileService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileService(repository, policy, resolver);
        when(resolver.defaultStorage()).thenReturn(storage);
        when(storage.storageId()).thenReturn("minio-main");
        when(storage.newLocation(anyString())).thenAnswer(invocation ->
                new ObjectLocation("minio-main", "private-files", invocation.getArgument(0), null));
        when(storage.put(any(), any(), anyLong())).thenAnswer(invocation -> {
            InputStream input = invocation.getArgument(1);
            input.transferTo(java.io.OutputStream.nullOutputStream());
            ObjectLocation location = invocation.getArgument(0);
            return new StoredObject(new ObjectLocation(location.storageId(), location.bucket(),
                    location.objectKey(), "version-1"), "etag-1");
        });
        when(storage.createDownloadUrl(any(), anyString(), any()))
                .thenReturn(URI.create("https://files.example.com/signed"));
    }

    @Test
    void savesChecksumAndVersionWithoutUsingUserFileNameAsObjectKey() throws Exception {
        AtomicReference<FileAssetEntity> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).update(any());
        FileUploadResult result = upload("../hello.txt", "hello");
        assertEquals("UPLOADED", result.status());
        assertEquals("hello.txt", result.fileName());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("hello".getBytes(StandardCharsets.UTF_8))), result.sha256());
        assertEquals("version-1", saved.get().getLocation().versionId());
        assertFalse(saved.get().getLocation().objectKey().contains("hello"));
        assertTrue(saved.get().getLocation().objectKey().endsWith(result.fileId()));
        verify(storage, never()).delete(any());
    }

    @Test
    void rejectsLargeFileBeforeDatabaseOrObjectWrite() {
        AppException e = assertThrows(AppException.class, () -> service.upload(
                new FileUploadCommand("large.txt", "text/plain", policy.maxFileSizeBytes() + 1, null),
                new ByteArrayInputStream(new byte[0])));
        assertEquals("FILE_TOO_LARGE", e.getCode());
        verifyNoInteractions(repository);
        verify(storage, never()).put(any(), any(), anyLong());
    }

    @Test
    void failedDatabaseCreateDoesNotUploadAnOrphan() {
        doThrow(new IllegalStateException("DB unavailable")).when(repository).create(any());
        assertEquals("FILE_UPLOAD_FAILED",
                assertThrows(AppException.class, () -> upload("test.txt", "hello")).getCode());
        verify(storage, never()).put(any(), any(), anyLong());
    }

    @Test
    void failedMetadataUpdateDeletesTheExactUploadedVersion() {
        doThrow(new IllegalStateException("DB unavailable")).doNothing().when(repository).update(any());
        assertThrows(AppException.class, () -> upload("test.txt", "hello"));
        verify(storage).delete(argThat(location -> "version-1".equals(location.versionId())));
    }

    @Test
    void failedCompensationLeavesRecoverableRecord() {
        AtomicReference<FileStatus> finalStatus = new AtomicReference<>();
        when(storage.createDownloadUrl(any(), anyString(), any()))
                .thenThrow(new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE));
        doThrow(new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE)).when(storage).delete(any());
        doAnswer(invocation -> {
            finalStatus.set(((FileAssetEntity) invocation.getArgument(0)).getStatus());
            return null;
        }).when(repository).update(any());
        assertThrows(AppException.class, () -> upload("test.txt", "hello"));
        assertEquals(FileStatus.CLEANUP_REQUIRED, finalStatus.get());
    }

    @Test
    void failedUploadReleasesConcurrencyPermit() {
        FileUploadPolicy singleUpload = new FileUploadPolicy(policy.maxFileSizeBytes(), 1,
                policy.downloadUrlTtl(), policy.allowedExtensions());
        service = new FileService(repository, singleUpload, resolver);
        doThrow(new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE))
                .when(storage).put(any(), any(), anyLong());
        for (int i = 0; i < 2; i++) {
            assertEquals("FILE_STORAGE_UNAVAILABLE",
                    assertThrows(AppException.class, () -> upload("test.txt", "hello")).getCode());
        }
        verify(storage, times(2)).put(any(), any(), anyLong());
    }

    @Test
    void attachmentReadRequiresOwnerAndCompletedUpload() {
        String id = "a7779ff9-3443-48ed-8b17-d32e767d63df";
        FileAssetEntity asset = FileAssetEntity.builder().fileId(id).ownerId("alice").status(FileStatus.UPLOADED).build();
        when(repository.findById(id)).thenReturn(asset);
        assertEquals("CHAT_ATTACHMENT_FORBIDDEN", assertThrows(AppException.class,
                () -> service.requireUploadedFile(id, "bob", true)).getCode());
        assertSame(asset, service.requireUploadedFile(id, "alice", false));
        asset.setStatus(FileStatus.UPLOADING);
        assertEquals("CHAT_ATTACHMENT_INVALID", assertThrows(AppException.class,
                () -> service.requireUploadedFile(id, "alice", false)).getCode());
        asset.setStatus(FileStatus.UPLOADED);
        asset.setOwnerId(null);
        assertEquals("CHAT_ATTACHMENT_FORBIDDEN", assertThrows(AppException.class,
                () -> service.requireUploadedFile(id, null, false)).getCode());
        assertSame(asset, service.requireUploadedFile(id, null, true));
    }

    @Test
    void readsOriginalStorageVersionAndRejectsChangedContent() throws Exception {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        ObjectLocation location = new ObjectLocation("old-storage", "private-files", "uploads/object", "version-1");
        FileAssetEntity asset = FileAssetEntity.builder().location(location).size(bytes.length)
                .sha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).build();
        when(resolver.resolve("old-storage")).thenReturn(storage);
        when(storage.openRead(location)).thenReturn(new ByteArrayInputStream(bytes),
                new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(bytes, service.readContent(asset, 100));
        assertEquals("CHAT_ATTACHMENT_CHECKSUM_MISMATCH", assertThrows(AppException.class,
                () -> service.readContent(asset, 100)).getCode());
        verify(resolver, never()).defaultStorage();
    }

    @Test
    void missingOrMalformedChecksumRejectsBeforeStorageRead() {
        FileAssetEntity asset = FileAssetEntity.builder().fileId("file")
                .location(new ObjectLocation("minio-main", "files", "uploads/file", null)).size(5).build();
        assertEquals("CHAT_ATTACHMENT_CHECKSUM_MISSING", assertThrows(AppException.class,
                () -> service.readContent(asset, 100)).getCode());
        asset.setSha256("invalid");
        assertEquals("CHAT_ATTACHMENT_CHECKSUM_MISSING", assertThrows(AppException.class,
                () -> service.readContent(asset, 100)).getCode());
        verify(storage, never()).openRead(any());
    }

    @Test
    void shorterAndLongerObjectsReportSizeMismatch() {
        ObjectLocation location = new ObjectLocation("minio-main", "files", "uploads/file", null);
        FileAssetEntity asset = FileAssetEntity.builder().fileId("file").location(location).size(5)
                .sha256("0".repeat(64)).build();
        when(resolver.resolve("minio-main")).thenReturn(storage);
        when(storage.openRead(location)).thenReturn(new ByteArrayInputStream(new byte[4]),
                new ByteArrayInputStream(new byte[6]));
        for (int i = 0; i < 2; i++) {
            assertEquals("CHAT_ATTACHMENT_SIZE_MISMATCH", assertThrows(AppException.class,
                    () -> service.readContent(asset, 100)).getCode());
        }
    }

    private FileUploadResult upload(String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.upload(new FileUploadCommand(name, "text/plain", bytes.length, null),
                new ByteArrayInputStream(bytes));
    }
}
