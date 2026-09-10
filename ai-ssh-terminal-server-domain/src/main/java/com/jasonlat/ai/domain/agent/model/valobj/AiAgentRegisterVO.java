package com.jasonlat.ai.domain.agent.model.valobj;

import com.google.adk.runner.InMemoryRunner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author jasonlat
 * 2026-03-31  20:56
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 应用名称
     */
    private String appName;
    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * session 默认过期时间
     */
    private long sessionExpireSeconds;

    /**
     * 智能体运行器
     */
    private InMemoryRunner runner;
}
