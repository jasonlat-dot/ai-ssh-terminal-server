package com.jasonlat.ai.infrastructure.model.settings;

import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * System One 意图决策适配器的运行参数。
 *
 * <p>该对象由 app 模块读取 YAML 后创建，基础设施层只依赖已经解析完成的参数，
 * 不直接依赖 Spring Boot 的 ConfigurationProperties。</p>
 *
 * @param enabled               是否启用 System One 意图分类
 * @param shadowMode            是否启用影子模式；影子模式会调用服务但不会接管路由
 * @param provider              当前选择的供应商
 * @param endpoint              完整的 /v1/systemone 接口地址
 * @param apiKey               Bearer API Key；Laya 未启用鉴权时允许为空
 * @param model                 请求中发送的模型名称
 * @param timeout               HTTP 连接和请求超时时间
 * @param minAnswerProbability  被选中答案的最低概率门槛
 */
public record SystemOneDecisionSettings(
        boolean enabled,
        boolean shadowMode,
        Provider provider,
        URI endpoint,
        String apiKey,
        String model,
        Duration timeout,
        double minAnswerProbability
) {

    /**
     * 当前支持的 System One 实现。
     */
    public enum Provider {

        /**
         * TypeSafe AI 提供的托管 Jev 服务。
         */
        JEV,

        /**
         * 本地或私有部署的开源 Laya 服务。
         */
        LAYA
    }

    /**
     * Record 紧凑构造器。
     *
     * <p>配置问题应在应用启动时立即暴露，避免等到第一条用户消息到来后
     * 才发现 URL、超时或密钥配置错误。</p>
     */
    public SystemOneDecisionSettings {
        Objects.requireNonNull(provider, "System One provider 不能为空");

        Objects.requireNonNull(endpoint, "System One endpoint 不能为空");

        Objects.requireNonNull(timeout, "System One timeout 不能为空");

        // 统一清理字符串边界空格，防止复制配置时带入不可见字符。
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();

        String scheme = endpoint.getScheme();

        if (!endpoint.isAbsolute()
                || scheme == null
                || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("System One endpoint 必须是绝对 HTTP/HTTPS 地址");
        }

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("System One timeout 必须大于 0");
        }

        if (!Double.isFinite(minAnswerProbability)
                || minAnswerProbability < 0.0
                || minAnswerProbability > 1.0) {
            throw new IllegalArgumentException("System One minAnswerProbability 必须位于 0 到 1 之间");
        }

        /*
         * Jev 是托管服务，启用时必须提供 API Key。
         * Laya 是否要求 API Key 取决于服务端是否设置 LAYA_API_KEY。
         */
        if (enabled && provider == Provider.JEV && apiKey.isBlank()) {
            throw new IllegalArgumentException("启用 Jev 时必须配置 JEV_API_KEY");
        }

        /*
         * Jev 请求协议要求 model。
         * Laya 可以根据输入自动选模型，因此这里只对 Jev 做强制校验。
         */
        if (enabled && provider == Provider.JEV && model.isBlank()) {
            throw new IllegalArgumentException("启用 Jev 时必须配置 Jev model");
        }
    }

    /**
     * 返回不包含 API Key 的配置描述。
     *
     * <p>避免 Spring 启动日志、调试器或异常信息意外打印密钥。</p>
     * @return 已脱敏的配置字符串
     */
    @Override
    public @NonNull String toString() {
        return "SystemOneDecisionSettings[enabled=" + enabled
                + ", shadowMode=" + shadowMode
                + ", provider=" + provider
                + ", endpoint=" + endpoint
                + ", model=" + model
                + ", timeout=" + timeout
                + ", minAnswerProbability=" + minAnswerProbability
                + "]";
    }
}