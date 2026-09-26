package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * @author jasonlat
 * 2026-04-04  14:26
 */
@Data
public class ChatRequest {

    private String agentId;

    private String userId;

    private String sessionId;

    /** 用户文字；有附件时可以为空。 */
    private String message;

    /** 已上传附件的引用，类型和存储位置由后端查询，前端不传 URL 或 Base64。 */
    private List<ChatAttachmentRequest> attachments;

    /** 仅由 Controller 从 Principal 填充，不接受请求 JSON 自报身份。 */
    @JsonIgnore
    private String authenticatedUserId;

    /**
     * SSH 终端会话 ID（用于智能体执行命令）
     * 如果未指定，系统将尝试从会话绑定中获取
     */
    private String terminalSessionId;

}
