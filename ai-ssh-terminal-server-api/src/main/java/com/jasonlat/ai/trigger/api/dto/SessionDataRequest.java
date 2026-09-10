package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;

/**
 * @author jasonlat
 * 2026-04-04  17:55
 */
@Data
public class SessionDataRequest {
    private String agentId;
    private String userId;
    private String sessionId;
}
