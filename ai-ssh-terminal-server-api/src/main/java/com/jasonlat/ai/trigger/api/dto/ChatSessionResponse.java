package com.jasonlat.ai.trigger.api.dto;

import java.util.Date;

/** 历史会话列表项。 */
public record ChatSessionResponse(String sessionId, String title, Integer messageCount,
                                  Date createdAt, Date updatedAt) {
}
