package com.jasonlat.ai.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH认证类型枚举
 */
@Getter
@AllArgsConstructor
public enum AuthTypeEnum {

    PASSWORD(1, "密码认证"),
    PRIVATE_KEY(2, "私钥认证");

    private final int code;
    private final String desc;

    public static AuthTypeEnum fromCode(int code) {
        for (AuthTypeEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        // 默认密码认证
        return PASSWORD;
    }

}
