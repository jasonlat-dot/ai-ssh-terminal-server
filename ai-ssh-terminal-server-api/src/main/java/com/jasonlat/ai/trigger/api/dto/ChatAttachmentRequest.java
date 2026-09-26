package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;

/** 聊天附件引用；文件必须先通过上传接口保存成功。 */
@Data
public class ChatAttachmentRequest {
    /** 上传响应中的 fileId，与 MinIO objectKey、下载 URL 不同。 */
    private String fileId;
}
