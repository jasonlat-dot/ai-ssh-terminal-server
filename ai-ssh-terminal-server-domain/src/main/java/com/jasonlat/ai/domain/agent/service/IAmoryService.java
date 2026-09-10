package com.jasonlat.ai.domain.agent.service;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * @author jasonlat
 * 2026-03-31  19:23
 * 装配智能体服务
 */
public interface IAmoryService {

    /**
     * 接收智能体
     * @param tables 智能体配置
     */
    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;
}
