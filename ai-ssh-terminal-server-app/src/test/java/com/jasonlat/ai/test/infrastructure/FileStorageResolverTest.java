package com.jasonlat.ai.test.infrastructure;

import com.jasonlat.ai.infrastructure.adapter.port.storage.DefaultObjectStorageResolver;
import com.jasonlat.ai.infrastructure.adapter.port.storage.minio.MinioObjectStorage;
import com.jasonlat.ai.infrastructure.config.FileStorageProperties;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageResolverTest {
    @Test
    void absentConfigurationDoesNotFailConstructionButRejectsUploadResolution() {
        FileStorageProperties properties = new FileStorageProperties();
        MinioObjectStorage minio = new MinioObjectStorage(properties);
        DefaultObjectStorageResolver resolver = new DefaultObjectStorageResolver(properties, List.of(minio));
        assertEquals("FILE_STORAGE_NOT_CONFIGURED",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }

    @Test
    void enabledButIncompleteConfigurationReturnsExplicitBusinessError() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.getMinio().setEnabled(true);
        DefaultObjectStorageResolver resolver = new DefaultObjectStorageResolver(
                properties, List.of(new MinioObjectStorage(properties)));
        assertEquals("FILE_STORAGE_CONFIG_INVALID",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }

    @Test
    void unknownDefaultDoesNotSilentlyFallBackToAnotherStorage() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setDefaultId("oss-main");
        DefaultObjectStorageResolver resolver = new DefaultObjectStorageResolver(properties, List.of());
        assertEquals("FILE_STORAGE_NOT_CONFIGURED",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }
}
