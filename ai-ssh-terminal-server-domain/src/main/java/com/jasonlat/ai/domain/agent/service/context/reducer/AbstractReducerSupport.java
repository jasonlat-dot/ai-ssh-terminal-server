package com.jasonlat.ai.domain.agent.service.context.reducer;


import java.util.Map;

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


}
