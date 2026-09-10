package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;

/**
 * @author jasonlat
 * 2026-04-04  14:26
 */
@Data
public class ChatRequest {

    private String agentId;

    private String userId;

    private String sessionId;

    private String message;
}
