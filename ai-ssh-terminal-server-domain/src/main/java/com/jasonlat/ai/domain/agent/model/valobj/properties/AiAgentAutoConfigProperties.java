package com.jasonlat.ai.domain.agent.model.valobj.properties;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * @author jasonlat
 * 2026-03-31  19:33
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {
    /**
     * 是否启用
     */
    private boolean enabled = false;

    /**
     * ai agent 表配置,可以配置多个agent配置
     */
    private Map<String, AiAgentConfigTableVO> tables = new HashMap<>(4);
}
