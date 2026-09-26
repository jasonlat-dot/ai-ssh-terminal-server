package com.jasonlat.ai.cases.file;

import com.jasonlat.ai.cases.IFileServiceCase;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadCommand;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadResult;
import com.jasonlat.ai.domain.file.service.IFileService;
import com.jasonlat.ai.trigger.api.dto.file.FileUploadResponseDTO;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
public class FileServiceCase implements IFileServiceCase {
    private final IFileService fileService;

    public FileServiceCase(IFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public FileUploadResponseDTO upload(MultipartFile file, String authenticatedUserId) {
        if (file == null) throw new AppException(ResponseCode.FILE_INVALID);
        // MultipartFile 留在应用层；领域服务只接收自己的命令对象和输入流。
        try (InputStream input = file.getInputStream()) {
            FileUploadResult result = fileService.upload(new FileUploadCommand(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), authenticatedUserId), input);
            return new FileUploadResponseDTO(result.fileId(), result.fileName(), result.contentType(),
                    result.size(), result.sha256(), result.status(), result.downloadUrl(), result.urlExpiresAt());
        } catch (IOException e) {
            throw new AppException(ResponseCode.FILE_UPLOAD_FAILED.getCode(),
                    ResponseCode.FILE_UPLOAD_FAILED.getInfo(), e);
        }
    }
}
