package com.jasonlat.ai.domain.file.model.entity;

import com.jasonlat.ai.domain.file.model.valobj.FileStatus;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import lombok.Builder;
import lombok.Data;

/** 文件元数据；文件正文始终保存在对象存储。 */
@Data
@Builder
public class FileAssetEntity {
    private String fileId;
    private String ownerId;
    private String originalName;
    /** 客户端声明的类型，仅作元数据，不用来决定文件是否安全。 */
    private String contentType;
    private long size;
    private String sha256;
    private ObjectLocation location;
    private String etag;
    private FileStatus status;
    private String errorCode;
}
