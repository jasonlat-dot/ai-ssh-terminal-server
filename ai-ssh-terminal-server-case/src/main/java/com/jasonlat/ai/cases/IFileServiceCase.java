package com.jasonlat.ai.cases;

import com.jasonlat.ai.trigger.api.dto.file.FileUploadResponseDTO;
import org.springframework.web.multipart.MultipartFile;

/** Controller 使用的统一文件门面，不关心实际存储厂商。 */
public interface IFileServiceCase {
    FileUploadResponseDTO upload(MultipartFile file, String authenticatedUserId);
}
