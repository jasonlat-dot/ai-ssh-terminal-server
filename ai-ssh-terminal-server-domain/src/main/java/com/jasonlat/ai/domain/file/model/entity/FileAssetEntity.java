package com.jasonlat.ai.domain.file.model.entity;

import com.jasonlat.ai.domain.file.model.valobj.FileStatus;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import lombok.Builder;
import lombok.Data;

/** 文件元数据；文件正文始终保存在对象存储。 */
@Data
@Builder
public class FileAssetEntity {
    /** 后端生成的唯一文件 ID，用于业务引用和问题追踪，不随下载地址过期而失效。 */
    private String fileId;

    /** 上传者的服务端认证身份；当前请求没有认证 Principal 时为空。 */
    private String ownerId;

    /** 去掉客户端路径后的展示文件名，例如 docker-error.png，不用于生成存储路径。 */
    private String originalName;

    /** 规范化后的客户端 MIME 类型，例如 image/png；仅作元数据，不代表真实内容已校验。 */
    private String contentType;

    /** 文件大小，单位为字节；上传成功前会核对实际读取的字节数。 */
    private long size;

    /** 后端读取上传流时计算的 SHA-256 十六进制摘要；尚未完成计算时为空。 */
    private String sha256;

    /** 文件的存储实例、桶、对象路径和版本号，用于定位对象及失败补偿。 */
    private ObjectLocation location;

    /** 存储服务返回的对象 ETag；分片上传等情况下不能将其直接当作文件 MD5。 */
    private String etag;

    /** 上传处理状态；UPLOADED 仅表示已存储，不表示已解析或通过安全扫描。 */
    private FileStatus status;

    /** 上传失败时记录的业务错误码；正常上传时为空，不保存存储 SDK 的敏感错误原文。 */
    private String errorCode;
}
