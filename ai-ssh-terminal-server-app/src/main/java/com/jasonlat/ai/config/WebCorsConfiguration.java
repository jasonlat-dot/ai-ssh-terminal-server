package com.jasonlat.ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 所有 HTTP 入口共用的 CORS 策略，仅接受 http(s)://127.0.0.1 网页来源。
 * CORS 校验的是 Origin，不是连接来源 IP；没有 Origin 的原生客户端请求不受它限制。
 */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfiguration {
    public static final String DEFAULT_ORIGINS =
            "http://127.0.0.1:[*],https://127.0.0.1:[*]";

    private final List<String> originPatterns;

    public WebCorsConfiguration(
            @Value("${app.config.cross-origin:" + DEFAULT_ORIGINS + "}")
            String configuredOrigins) {
        originPatterns = Arrays.stream(configuredOrigins.split(",", -1))
                .map(String::trim)
                .distinct()
                .toList();
        if (originPatterns.isEmpty() || originPatterns.stream().anyMatch(origin -> !isLoopbackOrigin(origin))) {
            throw new IllegalArgumentException(
                    "app.config.cross-origin 只允许 http(s)://127.0.0.1 来源；"
                            + "端口可写为具体端口或 [*]，不允许 *、localhost、Tauri 或外部域名");
        }
    }

    /** 注册在 Controller 之前，避免局部注解将全局来源限制扩大。 */
    @Bean
    public FilterRegistrationBean<CorsFilter> loopbackCorsFilter() {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOriginPatterns(originPatterns);
        policy.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        policy.setAllowedHeaders(List.of("*"));
        policy.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH));
        policy.setAllowCredentials(false);
        policy.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        CorsFilter filter = new CorsFilter(source) {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String origin = request.getHeader(HttpHeaders.ORIGIN);
                // Spring 默认跳过同源请求；显式校验使携带 Origin 的请求也统一遵守限制。
                if (origin != null && policy.checkOrigin(origin) == null) {
                    response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                super.doFilterInternal(request, response, chain);
            }
        };
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("loopbackCorsFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static boolean isLoopbackOrigin(String origin) {
        if (origin.equals("http://127.0.0.1:[*]") || origin.equals("https://127.0.0.1:[*]")) {
            return true;
        }
        if (!origin.matches("https?://127\\.0\\.0\\.1(?::[0-9]{1,5})?")) {
            return false;
        }
        try {
            int port = URI.create(origin).getPort();
            return port == -1 || (port >= 1 && port <= 65535);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
