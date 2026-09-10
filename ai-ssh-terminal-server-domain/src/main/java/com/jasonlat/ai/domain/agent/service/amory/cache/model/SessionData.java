package com.jasonlat.ai.domain.agent.service.amory.cache.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SessionData {
    private String userId;
    private String sessionId;    // 真实会话ID
    private String agentId;
    private long expireSeconds;  // 这个key要过期的秒数
}