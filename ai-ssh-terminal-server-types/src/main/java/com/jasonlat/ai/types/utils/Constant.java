package com.jasonlat.ai.types.utils;

import java.util.Locale;

/**
 * @author jasonlat
 */
public interface Constant {

    /**
     * UTF-8 字符集
     */
   String UTF8 = "UTF-8";

    /**
     * GBK 字符集
     */
   String GBK = "GBK";

    /**
     * 系统语言
     */
    Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /**
     * 资源映射路径 前缀
     */
    String RESOURCE_PREFIX = "/profile";

    /**
     * http请求
     */
    String HTTP = "http://";

    /**
     * https请求
     */
   String HTTPS = "https://";

   String FILE_DELIMETER = ",";

    String UNDERLINE = "_";


    /** 验证码和过期时间key */
    String LOGIN_CODE = "LOGIN_CODE_";
    Integer LOGIN_CODE_EXP = 5;

    /** 验证码和过期时间key */
    String REGISTER_CODE = "REGISTER_CODE_";
    Integer REGISTER_CODE_EXP = 5;


    /** sku 延迟队列 */
   String PRODUCT_STOCK_COUNT_QUERY_KEY = "product_stock_count_query_key";

    /** sku 库存 key */
   String PRODUCT_STOCK_COUNT_KEY = "product_stock_count_key_";

    /** sku 库存 key */
    String HASH_ALL_PRODUCT_STOCK_COUNT_KEY = "hash_all_product_stock_count_key";


}