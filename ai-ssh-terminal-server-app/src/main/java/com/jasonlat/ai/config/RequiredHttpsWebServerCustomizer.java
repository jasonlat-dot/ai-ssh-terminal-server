package com.jasonlat.ai.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 在 Tomcat 创建监听端口前核验真实 TLS 配置，阻止扩展配置新增明文入口。
 *
 * <p>仅固定 server.ssl.enabled 不足以约束代码中手动添加的 Connector。
 * 本类同时检查主连接器和附加连接器；HTTP 重定向端口也不允许，因为它仍接收明文请求。
 * 不使用 request.isSecure() 或 X-Forwarded-Proto 代替检查，避免代理请求头伪装 TLS。</p>
 */
@Component
public final class RequiredHttpsWebServerCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    /** @return 在常规容器配置之后添加最后的协议检查，不修改证书或端口。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 为主连接器安装最终检查，并检查所有独立配置的附加连接器。
     *
     * @param factory 已绑定 Spring SSL 配置、尚未创建 WebServer 的 Tomcat 工厂
     */
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // Spring Boot 在完成主连接器的证书和协议配置后调用此回调。
        // 放在回调内读取附加集合，可覆盖其他工厂定制器稍后追加的连接器。
        factory.addConnectorCustomizers(connector -> {
            requireTls(connector);
            factory.getAdditionalTomcatConnectors().forEach(this::requireTls);
        });
    }

    /**
     * 核验实际传输层启用了 TLS；只写 scheme=https 或 secure=true 不算启用 TLS。
     *
     * @param connector 待监听的主连接器或附加连接器
     * @throws IllegalStateException 存在明文 HTTP/AJP 等非 TLS 入口时拒绝启动
     */
    private void requireTls(Connector connector) {
        if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol)
                || !protocol.isSSLEnabled()) {
            // 启动前失败，而不是把明文业务请求收进来后才返回错误或重定向。
            throw new IllegalStateException("Java 后端禁止非 TLS 连接器，端口=" + connector.getPort()
                    + "；请移除 HTTP 重定向端口，或为该连接器配置真实的 HTTPS 证书");
        }
    }
}
