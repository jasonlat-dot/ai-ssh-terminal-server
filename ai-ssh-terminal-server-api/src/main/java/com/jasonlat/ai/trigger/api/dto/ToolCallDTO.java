package com.jasonlat.ai.trigger.api.dto;

/**
 * 一次由模型发起的工具调用。
 *
 * @param id   ADK/OpenAI 返回的工具调用 ID
 * @param name 工具名称
 * @param args JSON 格式的调用参数
 */
public record ToolCallDTO(String id, String name, String args) {
}
