package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.model.valobj.FileUploadCommand;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadResult;

import java.io.InputStream;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;

public interface IFileService {
    /**
     * 同步上传；返回时文件与元数据均已保存，输入流由调用方关闭。
     * 服务内部按默认配置选择存储；上传、签名和失败补偿始终使用同一实例。
     */
    FileUploadResult upload(FileUploadCommand command, InputStream input);

    /** 查询已上传文件并校验归属；身份必须来自服务端认证上下文。 */
    FileAssetEntity requireUploadedFile(String fileId, String authenticatedUserId, boolean allowAnonymous);

    /** 读取已通过校验的文件，限制真实字节数并核对摘要，不使用当前默认存储。 */
    byte[] readContent(FileAssetEntity asset, long maxBytes);
}
