package com.jasonlat.ai.domain.agent.service.amory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.springai.MessageConverter;

/**
 * 沿用 app 中的 MessageConverter 兼容实现，按每条用户消息转换各自的媒体。
 * 不再收集所有历史媒体后追加到第一条 UserMessage，否则会重复发送并串错轮次。
 */
public class LocalMessageConverter extends MessageConverter {
    public LocalMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
