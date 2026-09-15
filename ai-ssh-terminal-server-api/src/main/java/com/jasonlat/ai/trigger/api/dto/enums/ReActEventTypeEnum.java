package com.jasonlat.ai.trigger.api.dto.enums;

import lombok.Getter;

/** ReAct 流式响应的对外事件类型。 */
@Getter
public enum ReActEventTypeEnum {
    TEXT("text"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    ROUND_END("round_end"),
    DONE("done"),
    ERROR("error");

    private final String code;

    ReActEventTypeEnum(String code) {
        this.code = code;
    }

}
