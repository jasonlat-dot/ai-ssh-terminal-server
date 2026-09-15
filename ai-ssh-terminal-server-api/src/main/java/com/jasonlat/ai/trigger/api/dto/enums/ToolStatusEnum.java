package com.jasonlat.ai.trigger.api.dto.enums;

import lombok.Getter;

/** 工具调用对外展示的状态。 */
@Getter
public enum ToolStatusEnum {
    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    ERROR("error");

    private final String code;

    ToolStatusEnum(String code) {
        this.code = code;
    }

}
