package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;

/**
 * @author jasonlat
 * 2026-04-04  14:25
 */
@Data
public class CreateSessionRequest {

    private String agentId;

    private String userId;
}
