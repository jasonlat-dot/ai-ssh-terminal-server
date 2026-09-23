package com.jasonlat.ai.trigger.api.dto;

import java.util.Date;

/** 历史消息列表项；工具消息保留工具名称和调用 ID 供前端展示。 */
public record ChatMessageResponse(Long id, String role, String content,
                                  String toolName, String toolCallId, Date createdAt) {
}
