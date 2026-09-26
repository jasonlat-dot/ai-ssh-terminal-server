package com.jasonlat.ai.test.domain.file;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.domain.file.model.valobj.*;
import com.jasonlat.ai.domain.file.model.valobj.properties.FileUploadProperties;
import com.jasonlat.ai.domain.file.service.FileService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileServiceTest {
    private final ObjectStorageResolver resolver = mock(ObjectStorageResolver.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final IFileAssetRepository repository = mock(IFileAssetRepository.class);
    private final FileUploadProperties properties = new FileUploadProperties();
    private FileService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileService(resolver, repository, properties);
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
    void missingStorageFailsBeforeAnyDatabaseWrite() {
        when(resolver.defaultStorage()).thenThrow(new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED));
        AppException e = assertThrows(AppException.class, () -> upload("test.txt", "hello"));
        assertEquals("FILE_STORAGE_NOT_CONFIGURED", e.getCode());
        verifyNoInteractions(repository);
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
                new FileUploadCommand("large.txt", "text/plain", properties.getMaxFileSize().toBytes() + 1, null),
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
        properties.setMaxConcurrentUploads(1);
        service = new FileService(resolver, repository, properties);
        doThrow(new AppException(ResponseCode.FILE_STORAGE_UNAVAILABLE))
                .when(storage).put(any(), any(), anyLong());
        for (int i = 0; i < 2; i++) {
            assertEquals("FILE_STORAGE_UNAVAILABLE",
                    assertThrows(AppException.class, () -> upload("test.txt", "hello")).getCode());
        }
        verify(storage, times(2)).put(any(), any(), anyLong());
    }

    private FileUploadResult upload(String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.upload(new FileUploadCommand(name, "text/plain", bytes.length, null),
                new ByteArrayInputStream(bytes));
    }
}
