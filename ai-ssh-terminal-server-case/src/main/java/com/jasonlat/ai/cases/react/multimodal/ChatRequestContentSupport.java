package com.jasonlat.ai.cases.react.multimodal;

import com.jasonlat.ai.trigger.api.dto.ChatAttachmentRequest;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import java.util.List;

/** 统一文本与附件请求的基础校验，流式和同步入口采用相同规则。 */
public final class ChatRequestContentSupport {
    private ChatRequestContentSupport() { }

    public static boolean hasAttachments(ChatRequest request) {
        return request.getAttachments() != null && !request.getAttachments().isEmpty();
    }

    public static void validateAndNormalize(ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            if (!hasAttachments(request)) throw new AppException(ResponseCode.CHAT_CONTENT_REQUIRED);
            request.setMessage("请分析本次上传的附件。");
        }
        fileIds(request);
    }

    public static List<String> fileIds(ChatRequest request) {
        if (!hasAttachments(request)) return List.of();
        for (ChatAttachmentRequest attachment : request.getAttachments()) {
            if (attachment == null || attachment.getFileId() == null || attachment.getFileId().isBlank()) {
                throw new AppException(ResponseCode.CHAT_ATTACHMENT_INVALID);
            }
        }
        return request.getAttachments().stream().map(ChatAttachmentRequest::getFileId).toList();
    }
}
