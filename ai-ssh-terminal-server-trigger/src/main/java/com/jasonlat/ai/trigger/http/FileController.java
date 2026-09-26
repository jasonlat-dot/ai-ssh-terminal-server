package com.jasonlat.ai.trigger.http;

import com.jasonlat.ai.cases.IFileServiceCase;
import com.jasonlat.ai.trigger.api.dto.file.FileUploadResponseDTO;
import com.jasonlat.ai.trigger.api.response.Response;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

/** 独立文件上传入口，本阶段不绑定聊天消息，也不对外提供按 ID 读取或删除接口。 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final IFileServiceCase fileServiceCase;

    public FileController(IFileServiceCase fileServiceCase) {
        this.fileServiceCase = fileServiceCase;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponseDTO> upload(@RequestPart("file") MultipartFile file, Principal principal) {
        // 项目目前没有统一登录链路；已有认证 Principal 时记录身份，否则保留为空。
        String ownerId = principal == null ? null : principal.getName();
        return Response.success("上传成功", fileServiceCase.upload(file, ownerId));
    }
}
