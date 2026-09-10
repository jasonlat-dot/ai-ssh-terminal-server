package com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;


/**
 * 工具mcp 创建服务
 * @author jasonlat
 * 2026-04-01  18:45
 */
public interface IToolMcpCreateService {
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;
}
