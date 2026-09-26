package com.jasonlat.ai.domain.file.service;

import com.jasonlat.ai.domain.file.model.valobj.FileUploadCommand;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadResult;

import java.io.InputStream;

public interface IFileService {
    /** 同步上传；返回时文件与元数据均已保存，输入流由调用方关闭。 */
    FileUploadResult upload(FileUploadCommand command, InputStream input);
}
