package com.jasonlat.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgentTypeEnum {

    Loop("循环执行", "loop", "loopAgentNode"),
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    ;

    private String name;
    private String type;
    private String node;

    public static AgentTypeEnum formType(String type) {
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("agent-workflows.type is null!");
        }

        for (AgentTypeEnum agentType : values()) {
            if (agentType.getType().equalsIgnoreCase(type)) {
                return agentType;
            }
        }
        // 不符合的类型
        throw new IllegalArgumentException("only support 【agent-workflows.type】 ->【loop、parallel or sequential】");
    }

}