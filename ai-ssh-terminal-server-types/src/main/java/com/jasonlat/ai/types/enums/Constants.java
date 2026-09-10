package com.jasonlat.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class Constants {

    public final static String SPLIT = ",";

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public enum ResponseCode {
        SUCCESS("0000", "操作成功"),
        ERROR("0001", "操作失败"),
        UN_ERROR("0002", "未知失败"),
        ILLEGAL_PARAMETER("0003", "非法参数"),
        APP_ERROR("0004", "内部防攻击错误"),
        TOKEN_ERROR("Unauthorized-401-0003", "权限拦截"),
        ORDER_PRODUCT_ERR("OE001", "所购商品已下线，请重新选择下单商品"),
        ;

        private String code;
        private String info;

    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public enum ResponseError {
        DEFAULT_ERROR("操作失败，请重试"),
        DEFAULT_SUCCESS("操作成功，请重试"),
        ;

        private String info;

    }


}
