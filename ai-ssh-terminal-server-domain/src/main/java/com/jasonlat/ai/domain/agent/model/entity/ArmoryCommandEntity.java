package com.jasonlat.ai.domain.agent.model.entity;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令实体
 * @author jasonlat
 * 2026-03-31  20:50
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ArmoryCommandEntity {

    private AiAgentConfigTableVO aiAgentConfigTableVO;
}
