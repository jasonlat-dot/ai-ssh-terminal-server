package com.jasonlat.ai.trigger.api.response;


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


    public static <T> Response<T> error(String info) {
        return build("ERROR_0001", info, null);
    }

    public static <T> Response<T> success(String info, T data) {
        return build("SUCCESS_0000", info, data);
    }

}
