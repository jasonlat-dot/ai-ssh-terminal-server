package com.jasonlat.ai.infrastructure.model.settings;

/** SSH 代理连接参数；不依赖 Spring 配置绑定，日志中不输出凭据。 */
public record SshHttpProxySettings(
        boolean enabled, String host, int port, String username, String password) {

    public SshHttpProxySettings {
        if (enabled && (host == null || host.isBlank() || port <= 0 || port > 65535)) {
            throw new IllegalArgumentException("启用 SSH 代理时必须提供合法的主机和端口");
        }
    }

    public static SshHttpProxySettings disabled() {
        return new SshHttpProxySettings(false, null, 8080, null, null);
    }

    @Override
    public String toString() {
        return "SshHttpProxySettings[enabled=" + enabled + "]";
    }
}
