package com.jasonlat.ai.domain.agent.service.amory.matter.tool.adapter.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 集中创建 Spring AI 到 ADK 的工具适配器。
 */
@Component
public class SpringToolCallbackAdkAdapterFactory {

    private final ObjectMapper objectMapper;

    public SpringToolCallbackAdkAdapterFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BaseTool adapt(ToolCallback toolCallback) {
        if (toolCallback == null) {
            throw new IllegalArgumentException("toolCallback 不能为空");
        }

        return new SpringToolCallbackAdkAdapter(toolCallback, objectMapper);
    }
}