package com.jasonlat.ai.types.model;


import com.jasonlat.ai.types.enums.ResponseCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author jasonlat
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 70072393576454324L;

    private String code;
    private String info;
    private T data;


    public static <T> Response<T> build(String code, String msg, T data) {
        return new Response<T>(code, msg, data);
    }

    /**
     * 统一错误返回方法
     */
    public static <T> Response<T> error() {
        return Response.<T>builder()
                .code(ResponseCode.SERVER_ERROR.getCode())
                .info(ResponseCode.SERVER_ERROR.getInfo())
                .build();
    }


    public static <T> Response<T> ok() {
        return build(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
    }

    public static <T > Response<T> ok(T data) {
        return build(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), data);
    }

    public static <T> Response<T> ok(T data, String msg) {
        return build(ResponseCode.SUCCESS.getCode(), msg, data);
    }

    /**
     * 客户端错误
     */
    public static <T> Response<T> clientError() {
        return build(ResponseCode.CLIENT_A0001.getCode(), ResponseCode.CLIENT_A0001.getInfo(), null);
    }

    /**
     * 系统错误
     */
    public static <T> Response<T> systemError() {
        return build(ResponseCode.SYSTEM_B0001.getCode(), ResponseCode.SYSTEM_B0001.getInfo(), null);
    }

    /**
     * 第三方错误
     */
    public static <T> Response<T> thirdPartyError() {
        return build(ResponseCode.THIRD_PARTY_C0001.getCode(), ResponseCode.THIRD_PARTY_C0001.getInfo(), null);
    }

    public static <T> Response<T> error(ResponseCode resultCode, String msg, T data) {
        return build(resultCode.getCode(), msg, data);
    }

    public static <T> Response<T> error(ResponseCode resultCode, T data) {
        return build(resultCode.getCode(), resultCode.getInfo(), data);
    }

    public static <T> Response<T> error(ResponseCode resultCode, String msg) {
        return build(resultCode.getCode(), msg, null);
    }

    public static <T> Response<T> error(ResponseCode resultCode) {
        return build(resultCode.getCode(), resultCode.getInfo(), null);
    }

    public static <T> Response<T> error(String info) {
        return build("ERROR_0001", info, null);
    }
}
