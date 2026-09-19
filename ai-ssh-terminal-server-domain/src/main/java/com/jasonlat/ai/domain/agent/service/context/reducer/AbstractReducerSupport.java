package com.jasonlat.ai.domain.agent.service.context.reducer;


import java.util.Map;
import java.util.Set;

public abstract class AbstractReducerSupport implements MessageReducer {

    /**
     * 粗略估算消息内容占用的 Token 数量。
     * 注意：
     * 该方法并不是严格的 Tokenizer 计算，只用于上下文长度控制、
     * 消息裁剪等对精度要求不高的场景。
     * 粗略规则：
     * - 中文字符：约 1 个字符 ≈ 1 Token
     * - 英文 / 数字 / 符号：约 4 个字符 ≈ 1 Token
     *
     * @param message 消息对象
     * @return 估算 Token 数
     */
    protected int estimateToken(Map<String, Object> message) {

        Object contentObj = message.get("content");
        if (contentObj == null) {
            return 0;
        }

        String content = contentObj.toString();
        if (content.isEmpty()) {
            return 0;
        }

        int chineseCount = 0;
        int otherCount = 0;

        for (char c : content.toCharArray()) {

            // 判断是否为常见中文字符
            if (c >= '\u4E00' && c <= '\u9FFF') {
                chineseCount++;
            } else {
                otherCount++;
            }
        }

        // 中文大致按照 1 字 ≈ 1 Token
        // 英文、数字、符号大致按照 4 字符 ≈ 1 Token
        return chineseCount + (int) Math.ceil(otherCount / 4.0);
    }


    /**
     * 判断一条消息是否是当前工具调用组对应的 tool result。
     * 兼容 OpenAI (role=tool, tool_call_id) 和 Anthropic (type=tool_result, tool_use_id)
     *
     * <p>案例：
     * <pre>
     *   当前组 toolCallIds = {"call_1", "call_2"}
     *
     *   messageA = {role=tool, tool_call_id=call_1}      -> true
     *   messageB = {type=tool_result, tool_use_id=call_2} -> true
     *   messageC = {role=assistant, content="继续分析"}     -> false
     * </pre>
     *
     * @param message 待匹配消息
     * @param toolCallIds 当前工具调用组持有的 tool_call_id 集合
     * @return true 表示该消息属于当前工具调用组
     */
    protected boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        String role = stringValue(message.get("role"));
        String type = stringValue(message.get("type"));

        if (!"tool".equals(role) && !"tool_result".equals(type)) {
            return false;
        }

        String toolCallId = stringValue(message.get("tool_call_id"));
        if (toolCallId.isEmpty()) {
            toolCallId = stringValue(message.get("tool_use_id"));
        }

        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    /**
     * 安全获取对象字符串值，避免 null 参与后续判断。
     *
     * @param value 原始对象
     * @return 非 null 字符串
     */
    protected String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }



}
