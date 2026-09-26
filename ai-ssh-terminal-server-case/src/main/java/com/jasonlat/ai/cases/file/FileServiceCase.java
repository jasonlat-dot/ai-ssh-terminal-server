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

/** 文件上传应用门面：选择存储实例，隔离 Web 上传类型，管理输入流并转换领域结果。 */
@Service
public class FileServiceCase implements IFileServiceCase {
    /** 执行上传业务规则、元数据状态变更及失败补偿。 */
    private final IFileService fileService;

    public FileServiceCase(IFileService fileService) {
        this.fileService = fileService;
    }

    /** authenticatedUserId 来自服务端认证上下文，不接受前端自报身份作为文件归属。 */
    @Override
    public FileUploadResponseDTO upload(MultipartFile file, String authenticatedUserId) {

        if (file == null) throw new AppException(ResponseCode.FILE_INVALID);

        // MultipartFile 留在应用层；领域服务接收命令、输入流和选定的存储端口。
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
