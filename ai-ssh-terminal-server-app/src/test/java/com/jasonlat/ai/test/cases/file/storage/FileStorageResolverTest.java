package com.jasonlat.ai.test.cases.file.storage;

import com.jasonlat.ai.domain.file.service.storage.resolver.DefaultIObjectStorageResolver;
import com.jasonlat.ai.config.properties.FileStorageProperties;
import com.jasonlat.ai.config.FileServiceConfiguration;
import com.jasonlat.ai.domain.file.service.storage.MinioIObjectStorageService;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageResolverTest {
    @Test
    void absentConfigurationDoesNotFailConstructionButRejectsUploadResolution() {
        FileStorageProperties properties = new FileStorageProperties();
        MinioIObjectStorageService minio = new MinioIObjectStorageService(new FileServiceConfiguration().minioStorageSettings(properties));
        DefaultIObjectStorageResolver resolver = new DefaultIObjectStorageResolver(properties.getDefaultId(), List.of(minio));
        assertEquals("FILE_STORAGE_NOT_CONFIGURED",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }

    @Test
    void enabledButIncompleteConfigurationReturnsExplicitBusinessError() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.getMinio().setEnabled(true);
        DefaultIObjectStorageResolver resolver = new DefaultIObjectStorageResolver(
                properties.getDefaultId(), List.of(new MinioIObjectStorageService(new FileServiceConfiguration().minioStorageSettings(properties))));
        assertEquals("FILE_STORAGE_CONFIG_INVALID",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }

    @Test
    void unknownDefaultDoesNotSilentlyFallBackToAnotherStorage() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setDefaultId("oss-main");
        DefaultIObjectStorageResolver resolver = new DefaultIObjectStorageResolver(properties.getDefaultId(), List.of());
        assertEquals("FILE_STORAGE_NOT_CONFIGURED",
                assertThrows(AppException.class, resolver::defaultStorage).getCode());
    }
}
