package com.jasonlat.ai.trigger.http;

import com.jasonlat.ai.cases.IFileServiceCase;
import com.jasonlat.ai.trigger.api.dto.file.FileUploadResponseDTO;
import com.jasonlat.ai.trigger.api.response.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.security.Principal;

/** 独立文件上传入口，本阶段不绑定聊天消息，也不对外提供按 ID 读取或删除接口。 */
@RestController
@RequestMapping("/api/v1/files")
@CrossOrigin("*")
public class FileController {
    /** 处理文件上传领域流程并返回上传结果的应用门面。 */
    private final IFileServiceCase fileServiceCase;

    /** 注入统一文件应用门面。 */
    public FileController(IFileServiceCase fileServiceCase) {
        this.fileServiceCase = fileServiceCase;
    }

    /** 接收上传文件，并把本地存储产生的相对下载地址补全为当前后端的绝对地址。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponseDTO> upload(
            @RequestPart("file") MultipartFile file,
            Principal principal,
            HttpServletRequest request) {
        // 项目目前没有统一登录链路；已有认证 Principal 时记录身份，否则保留为空。
        String ownerId = principal == null ? null : principal.getName();
        FileUploadResponseDTO uploaded = fileServiceCase.upload(file, ownerId);
        // MinIO 返回的地址本身已经是绝对地址；只有本地存储的相对地址需要 Web 层补全。
        return Response.success("上传成功", absoluteDownloadUrl(uploaded, request));
    }

    /** 使用当前上传请求的协议、主机、端口和路径前缀补全相对下载地址。 */
    private FileUploadResponseDTO absoluteDownloadUrl(
            FileUploadResponseDTO uploaded,
            HttpServletRequest request) {
        URI download = URI.create(uploaded.downloadUrl());
        if (download.isAbsolute()) {
            return uploaded;
        }

        // 上传地址以 /files 结尾，相对地址 files/local 会解析为同一前缀下的 /files/local。
        // requestURL 会保留当前实际端口；正确配置 Forwarded Headers 后也能反映代理外部地址。
        URI uploadRequest = URI.create(request.getRequestURL().toString());
        String absoluteUrl = uploadRequest.resolve(download).toString();
        return new FileUploadResponseDTO(
                uploaded.fileId(),
                uploaded.fileName(),
                uploaded.contentType(),
                uploaded.size(),
                uploaded.sha256(),
                uploaded.status(),
                absoluteUrl,
                uploaded.urlExpiresAt());
    }
}
