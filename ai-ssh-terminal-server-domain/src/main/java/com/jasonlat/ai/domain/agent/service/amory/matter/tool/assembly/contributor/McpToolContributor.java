package com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.contributor;

import com.google.adk.tools.BaseTool;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.mcp.client.IToolMcpCreateService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.mcp.client.factory.DefaultMcpClientFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.adapter.springai.SpringToolCallbackAdkAdapterFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.AgentToolContributor;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.assembly.valobj.AgentToolAssemblyContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据 Agent 的 MCP 配置加载工具，
 * 并将 Spring AI ToolCallback 转换为 ADK BaseTool。
 */
@Component
public class McpToolContributor implements AgentToolContributor {

    private final DefaultMcpClientFactory mcpClientFactory;
    private final SpringToolCallbackAdkAdapterFactory adapterFactory;

    public McpToolContributor(DefaultMcpClientFactory mcpClientFactory, SpringToolCallbackAdkAdapterFactory adapterFactory) {

        this.mcpClientFactory = mcpClientFactory;
        this.adapterFactory = adapterFactory;
    }

    @Override
    public List<? extends BaseTool> contribute(AgentToolAssemblyContext context) {

        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = context.chatModelConfig();

        if (chatModelConfig == null || chatModelConfig.getToolMcpList() == null || chatModelConfig.getToolMcpList().isEmpty()) {
            return List.of();
        }

        List<BaseTool> tools = new ArrayList<>();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp config : chatModelConfig.getToolMcpList()) {

            try {
                IToolMcpCreateService createService = mcpClientFactory.getToolMcpCreateService(config);

                ToolCallback[] callbacks = createService.buildToolCallback(config);

                if (callbacks == null) {
                    throw new IllegalStateException("MCP 工具创建服务返回了 null");
                }

                for (ToolCallback callback : callbacks) {
                    tools.add(adapterFactory.adapt(callback));
                }

            } catch (Exception exception) {
                throw new IllegalStateException("MCP 工具装配失败，agentName=" + context.agentConfig().getName(), exception);
            }
        }

        return List.copyOf(tools);
    }
}