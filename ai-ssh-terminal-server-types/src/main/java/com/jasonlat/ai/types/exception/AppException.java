package com.jasonlat.ai.types.exception;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 自定义异常 jasonlat
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public class AppException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 5317680961212299217L;

    /** 异常码 */
    private final String code;

    /** 异常信息 */
    private final String info;

    public AppException(String info) {
        this.code = "SERVER_ERROR";
        this.info = info;
    }

    public AppException(String code, Throwable cause) {
        this.code = code;
        this.info = "";
        super.initCause(cause);
    }

    public AppException(String code, String message) {
        this.code = code;
        this.info = message;
    }

    public AppException(String code, String message, Throwable cause) {
        this.code = code;
        this.info = message;
        super.initCause(cause);
    }

    @Override
    public String toString() {
        return "AppException{" +
                "code='" + code + '\'' +
                ", info='" + info + '\'' +
                "} " + super.toString();
    }
}