package com.jasonlat.ai.domain.agent.service.amory.node.utils;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO.Module.AiApi.ProxySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class OpenAiProxyHttpClient {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiProxyHttpClient.class);

    private OpenAiProxyHttpClient() {
    }

    public static HttpClient create(ProxySettings settings) {
        if (settings == null || !settings.isEnabled()) {
            throw new IllegalArgumentException("代理配置必须存在且已启用");
        }
        if (settings.getHost() == null || settings.getHost().isBlank()) {
            throw new IllegalArgumentException("代理 host 不能为空");
        }
        if (settings.getPort() < 1 || settings.getPort() > 65535) {
            throw new IllegalArgumentException("代理 port 必须在 1 到 65535 之间");
        }

        Proxy proxy = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(settings.getHost().trim(), settings.getPort())
        );
        List<Pattern> bypassPatterns =
                compileBypassPatterns(settings.getNonProxyHosts());

        ProxySelector proxySelector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                Objects.requireNonNull(uri, "uri 不能为空");

                String targetHost = uri.getHost();
                if (targetHost != null && bypassPatterns.stream()
                        .anyMatch(pattern -> pattern.matcher(targetHost).matches())) {
                    return List.of(Proxy.NO_PROXY);
                }

                return List.of(proxy);
            }

            @Override
            public void connectFailed(
                    URI uri,
                    SocketAddress socketAddress,
                    IOException exception
            ) {
                log.warn(
                        "OpenAI 代理连接失败 | uri:{} | proxy:{}",
                        uri,
                        socketAddress,
                        exception
                );
            }
        };

        return HttpClient.newBuilder()
                .proxy(proxySelector)
                .build();
    }

    private static List<Pattern> compileBypassPatterns(List<String> hostRules) {
        if (hostRules == null) {
            return List.of();
        }

        return hostRules.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(rule -> !rule.isEmpty())
                .map(OpenAiProxyHttpClient::compileGlob)
                .toList();
    }

    private static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder("^");

        for (char character : glob.toCharArray()) {
            if (character == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }

        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}