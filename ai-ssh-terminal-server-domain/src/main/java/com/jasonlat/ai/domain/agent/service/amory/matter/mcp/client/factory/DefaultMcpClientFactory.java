package com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.factory;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.IToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.impl.LocalToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.impl.SSEToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.impl.StdioToolMcpCreateService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;


@Component
public class DefaultMcpClientFactory {
    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;
    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;
    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    public IToolMcpCreateService getToolMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sseConfig = toolMcp.getSse();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdioConfig = toolMcp.getStdio();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        if (null != sseConfig) return sseToolMcpCreateService;
        if (null != stdioConfig) return stdioToolMcpCreateService;
        if (null != local) return localToolMcpCreateService;
        throw new AppException(ResponseCode.NOT_SUPPORT_METHOD.getCode(), ResponseCode.NOT_SUPPORT_METHOD.getCode());
    }
}
