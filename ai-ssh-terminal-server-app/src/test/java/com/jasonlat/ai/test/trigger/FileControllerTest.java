package com.jasonlat.ai.test.trigger;

import com.jasonlat.ai.cases.IFileServiceCase;
import com.jasonlat.ai.trigger.api.dto.file.FileUploadResponseDTO;
import com.jasonlat.ai.trigger.http.FileController;
import com.jasonlat.ai.trigger.http.advice.FileExceptionHandler;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileControllerTest {
    @Test
    void returns503AndBusinessCodeWhenStorageIsMissing() throws Exception {
        IFileServiceCase service = mock(IFileServiceCase.class);
        when(service.upload(any(), isNull())).thenThrow(new AppException(ResponseCode.FILE_STORAGE_NOT_CONFIGURED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileController(service))
                .setControllerAdvice(new FileExceptionHandler()).build();
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "test.txt", "text/plain", new byte[]{1})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FILE_STORAGE_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.info").isNotEmpty());
    }

    @Test
    void missingFilePartReturnsStructured400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(IFileServiceCase.class)))
                .setControllerAdvice(new FileExceptionHandler()).build();
        mvc.perform(multipart("/api/v1/files"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_INVALID"));
    }

    /** 本地存储相对下载地址应自动使用上传请求的主机和端口。 */
    @Test
    void expandsLocalDownloadUrlFromCurrentUploadRequest() throws Exception {
        IFileServiceCase service = mock(IFileServiceCase.class);
        when(service.upload(any(), isNull())).thenReturn(new FileUploadResponseDTO(
                "file-id", "test.txt", "text/plain", 1, "sha", "UPLOADED",
                "files/local?storageId=local-main&signature=signed", Instant.parse("2026-09-28T12:00:00Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileController(service)).build();

        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "test.txt", "text/plain", new byte[]{1}))
                        .with(request -> {
                            request.setServerName("127.0.0.1");
                            request.setServerPort(9123);
                            request.setScheme("http");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl")
                        .value("http://127.0.0.1:9123/api/v1/files/local?storageId=local-main&signature=signed"));
    }
}
