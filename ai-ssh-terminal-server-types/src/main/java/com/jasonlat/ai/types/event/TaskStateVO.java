package com.jasonlat.ai.types.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author jasonlat
 */
@Getter
@AllArgsConstructor
public enum TaskStateVO {

    create("create", "创建"),
    complete("complete", "发送完成"),
    fail("fail", "发送失败"),
    ;

    private final String code;
    private final String desc;

}