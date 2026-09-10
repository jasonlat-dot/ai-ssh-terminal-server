package com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.impl;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.client.IToolMcpCreateService;
import com.jasonlat.ai.types.utils.BeanUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class LocalToolMcpCreateService implements IToolMcpCreateService {

    @Resource
    private BeanUtils beanUtils;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters localMcpConfig = toolMcp.getLocal();
        String beanName = localMcpConfig.getBeanName();
        log.info("localMcpConfig: {}", localMcpConfig);
        if (StringUtils.isBlank(beanName)) {
            throw new RuntimeException(("local mcp beanName is null"));
        }
        ToolCallbackProvider localMcpCallbackProvider = beanUtils.getBean(beanName, ToolCallbackProvider.class);
        return localMcpCallbackProvider.getToolCallbacks();
    }
}
