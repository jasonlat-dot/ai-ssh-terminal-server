package com.jasonlat.ai.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后端连接 SSH 服务器时使用的全局 HTTP CONNECT 代理配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai.ssh.http-proxy")
public class SshHttpProxyProperties implements InitializingBean {

    /** 是否让后端发起的 SSH TCP 连接经过 HTTP 代理。 */
    private boolean enabled;

    /** HTTP 代理服务器地址。 */
    private String host;

    /** HTTP 代理服务器端口。 */
    private int port = 8080;

    /** 可选的代理认证用户名。 */
    private String username;

    /** 可选的代理认证密码。 */
    private String password;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ai.ssh.http-proxy.host 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("ai.ssh.http-proxy.port 必须在 1 到 65535 之间");
        }
    }
}
