package com.jasonlat.ai.test.cases.file;

import com.jasonlat.ai.cases.file.FileServiceCase;
import com.jasonlat.ai.cases.file.storage.ObjectStorageResolver;
import com.jasonlat.ai.domain.file.service.IFileService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 存储选择移到应用层后，缺失配置的回归场景也在此验证。 */
class FileServiceCaseTest {
    @Test
    void missingStorageFailsBeforeOpeningStreamOrEnteringDomain() {
        ObjectStorageResolver resolver = mock(ObjectStorageResolver.class);
        IFileService service = mock(IFileService.class);
        MultipartFile file = mock(MultipartFile.class);
        when(resolver.defaultStorage()).thenThrow(new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED));

        FileServiceCase serviceCase = new FileServiceCase(resolver, service);
        AppException e = assertThrows(AppException.class, () -> serviceCase.upload(file, null));

        assertEquals("FILE_STORAGE_NOT_CONFIGURED", e.getCode());
        verifyNoInteractions(file, service);
    }
}
