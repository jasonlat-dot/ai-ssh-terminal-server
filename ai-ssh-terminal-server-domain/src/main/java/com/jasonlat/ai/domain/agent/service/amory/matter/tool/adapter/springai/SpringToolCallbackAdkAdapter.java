package com.jasonlat.ai.domain.agent.service.amory.matter.tool.adapter.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 将 Spring AI ToolCallback 适配成 Google ADK BaseTool。
 * Spring AI 工具负责真正的业务执行，ADK 负责：
 * 1. 把工具定义发送给大模型。
 * 2. 接收模型返回的工具调用。
 * 3. 调用本适配器。
 * 4. 将执行结果继续交给模型。
 */
public class SpringToolCallbackAdkAdapter extends BaseTool {

    private static final String RESULT_KEY = "result";

    private final ToolCallback delegate;
    private final ObjectMapper objectMapper;
    private final FunctionDeclaration declaration;

    public SpringToolCallbackAdkAdapter(ToolCallback delegate, ObjectMapper objectMapper) {

        super(delegate.getToolDefinition().name(), delegate.getToolDefinition().description());

        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.declaration = createDeclaration(delegate.getToolDefinition());
    }

    /**
     * ADK 会把该声明发送给模型。
     * parametersJsonSchema 可以直接保留 Spring AI 原始 JSON Schema，
     * 不需要手动转换每个字段类型。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
    }

    /**
     * ADK 工具执行入口。
     * 模型参数先序列化为 JSON，再交给原始 Spring AI ToolCallback。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {

        return Single.fromCallable(() -> {
            Map<String, Object> safeArgs = args == null ? Map.of() : args;

            String requestJson = objectMapper.writeValueAsString(safeArgs);

            /*
             * 将 ADK Session state 复制到 Spring AI ToolContext。
             * SkillsTool 当前不依赖这些状态，但其他 Spring AI 工具可以使用。
             */
            Map<String, Object> springContextData = new HashMap<>();
            if (toolContext != null) {
                springContextData.putAll(toolContext.state());
            }

            org.springframework.ai.chat.model.ToolContext springToolContext =
                    new org.springframework.ai.chat.model.ToolContext(springContextData);

            String response = delegate.call(requestJson, springToolContext);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(RESULT_KEY, response);
            return result;
        });
    }

    private FunctionDeclaration createDeclaration(ToolDefinition definition) {

        Object inputSchema = parseInputSchema(definition.name(), definition.inputSchema());

        return FunctionDeclaration.builder()
                .name(definition.name())
                .description(definition.description())
                .parametersJsonSchema(inputSchema)
                .build();
    }

    private Object parseInputSchema(
            String toolName,
            String inputSchema) {

        if (inputSchema == null || inputSchema.isBlank()) {
            return Map.of(
                    "type", "object",
                    "properties", Map.of());
        }

        try {
            return objectMapper.readValue(
                    inputSchema,
                    Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Spring AI 工具参数 Schema 解析失败，toolName="
                            + toolName
                            + ", schema="
                            + inputSchema,
                    exception);
        }
    }
}