package com.jasonlat.ai.types.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateTimeUtils {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 将 String 类型的日期时间转换为 LocalDateTime 类型
     * @param dateTimeStr 要转换的日期时间字符串，格式为 yyyy-MM-dd HH:mm
     * @return 转换后的 LocalDateTime 对象，如果转换失败返回 null
     */
    public static LocalDateTime stringToLocalDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, FORMATTER);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将 LocalDateTime 类型的日期时间转换为指定格式（yyyy-MM-dd HH:mm）的 String 类型
     * @param localDateTime 要转换的 LocalDateTime 对象
     * @return 转换后的日期时间字符串，如果传入为 null 则返回 null
     */
    public static String localDateTimeToString(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.format(FORMATTER);
    }
}