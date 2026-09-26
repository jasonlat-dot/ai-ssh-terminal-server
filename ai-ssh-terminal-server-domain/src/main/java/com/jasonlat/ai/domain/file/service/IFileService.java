package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.adapter.port.ObjectStoragePort;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadCommand;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadResult;

import java.io.InputStream;

public interface IFileService {
    /**
     * 同步上传；返回时文件与元数据均已保存，输入流由调用方关闭。
     * storage 由应用层选择并检查配置，不能为空；上传、签名和失败补偿始终使用同一实例。
     */
    FileUploadResult upload(FileUploadCommand command, InputStream input, ObjectStoragePort storage);
}
