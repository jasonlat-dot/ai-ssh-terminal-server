package com.jasonlat.ai.domain.agent.service.amory.factory;


import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.node.validate.AgentYmlConfigValidateNode;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jasonlat
 * 2026-04-04  12:33
 */
@Component
public class DefaultValidateFactory {

    @Resource
    private AgentYmlConfigValidateNode agentYmlConfigValidateNode;

    public StrategyHandler<List<AiAgentConfigTableVO>, DefaultValidateFactory.DynamicContext, Boolean> validateStrategyHandler() {
        return agentYmlConfigValidateNode;
    }

    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        private Map<String, Object> dataObjects = new HashMap<>(8);

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }
        }
}
