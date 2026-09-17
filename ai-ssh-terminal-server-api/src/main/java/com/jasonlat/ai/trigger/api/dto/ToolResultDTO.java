package com.jasonlat.ai.trigger.api.dto;

/**
 * 一次工具调用的结构化执行结果。
 *
 * @param id      对应 {@link ToolCallDTO#id()} 的调用 ID
 * @param name    工具名称
 * @param content 工具输出
 * @param args    便于展示的关键参数；SSH 工具中为命令文本
 * @param status  success / error
 */
public record ToolResultDTO(String id, String name, String content, String args, String status) {
}
