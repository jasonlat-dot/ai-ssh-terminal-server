package com.jasonlat.ai.trigger.api.dto;

import lombok.Data;

/**
 * 智能体配置响应对象
 * @author jasonlat
 * 2026-04-04  14:23
 */
@Data
public class AgentConfigResponse {
    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;
}
