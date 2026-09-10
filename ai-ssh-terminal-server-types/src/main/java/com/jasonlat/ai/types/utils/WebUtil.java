package com.jasonlat.ai.types.utils;

import com.jasonlat.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletResponse;


public class WebUtil {
    /**
     * 将字符串渲染到客户端
     *
     * @param response 渲染对象
     * @param string 待渲染的字符串
     */
    public static void renderString(HttpServletResponse response, String string) {
        try {
            response.setStatus(200);
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");
            response.getWriter().print(string);
        }
        catch (Exception e) {
            throw new AppException("渲染客户端出错");
        }
    }
}