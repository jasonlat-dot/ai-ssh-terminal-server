package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Jev/Laya System One 外部配置。
 *
 * <p>配置前缀为 {@code ai.decision}。</p>
 *
 * <p>应用可以同时保存 Jev 和 Laya 两套参数，
 * 运行时通过 provider 选择其中一套，不需要修改 Java 代码。</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.decision")
public class DecisionProviderProperties implements InitializingBean {

    /**
     * 是否启用 System One 快速意图分类。
     *
     * <p>默认为 false，保证未配置 Jev/Laya 时保持原有行为。</p>
     */
    private boolean enabled = false;

    /**
     * 是否启用影子模式。
     *
     * <p>true 时仍会调用 Jev/Laya 并记录结果，
     * 但最终继续使用原有 LLM 分类器。</p>
     */
    private boolean shadowMode = true;

    /**
     * 当前选中的供应商。
     */
    private Provider provider = Provider.LAYA;

    /**
     * Jev 托管服务配置。
     */
    private EndpointProperties jev = EndpointProperties.jevDefaults();

    /**
     * Laya 本地或私有服务配置。
     */
    private EndpointProperties laya = EndpointProperties.layaDefaults();

    /**
     * 配置文件允许选择的供应商。
     */
    public enum Provider {

        /**
         * TypeSafe AI Jev 托管服务。
         */
        JEV,

        /**
         * Laya 开源自部署服务。
         */
        LAYA
    }

    /**
     * 单个供应商的连接参数。
     */
    @Data
    public static class EndpointProperties {

        /**
         * 完整的 System One 接口地址。
         *
         * <p>地址应直接包含 /v1/systemone。</p>
         */
        private URI url;

        /**
         * Bearer API Key。
         *
         * <p>Jev 必填。Laya 只有在服务端设置 LAYA_API_KEY 时才需要。</p>
         */
        private String apiKey = "";

        /**
         * 请求使用的模型名。
         *
         * <p>Jev 推荐 jev-latest；中文 Laya 推荐 multilingual。</p>
         */
        private String model = "";

        /**
         * HTTP 连接和请求超时时间。
         */
        private Duration timeout = Duration.ofSeconds(3);

        /**
         * 被选中答案的最低概率。
         *
         * <p>该值对应 probabilities[choice]，
         * 不是供应商原生 confidence。</p>
         */
        private double minAnswerProbability = 0.72;

        /**
         * 创建 Jev 默认配置。
         *
         * @return Jev 默认参数
         */
        private static EndpointProperties jevDefaults() {
            EndpointProperties properties = new EndpointProperties();

            properties.setUrl(URI.create("https://api.typesafe.ai/v1/systemone"));

            properties.setModel("jev-latest");

            properties.setTimeout(Duration.ofSeconds(3));

            properties.setMinAnswerProbability(0.72);

            return properties;
        }

        /**
         * 创建 Laya 默认配置。
         *
         * @return Laya 默认参数
         */
        private static EndpointProperties layaDefaults() {
            EndpointProperties properties = new EndpointProperties();

            properties.setUrl(URI.create("http://127.0.0.1:8000/v1/systemone"));

            /*
             * 当前业务以中文消息为主，因此默认使用 multilingual。
             * 配置为空字符串时，适配器会省略 model，让 Laya 自动路由。
             */
            properties.setModel("multilingual");

            /*
             * 本地 CPU 推理可能比 Jev 托管服务慢，
             * 因此给 Laya 更宽松的默认超时。
             */
            properties.setTimeout(Duration.ofSeconds(8));

            properties.setMinAnswerProbability(0.72);

            return properties;
        }
    }

    /**
     * 获取当前 provider 对应的配置。
     *
     * @return 当前选中的 Jev 或 Laya 参数
     */
    public EndpointProperties selected() {
        return switch (provider) {
            case JEV -> jev;
            case LAYA -> laya;
        };
    }

    /**
     * Spring 完成属性绑定后执行配置校验。
     *
     * <p>配置错误会让应用启动失败并给出明确错误，
     * 而不是等用户发送第一条消息时才失败。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (provider == null) {
            throw new IllegalArgumentException("ai.decision.provider 不能为空");
        }

        EndpointProperties selected = selected();

        if (selected == null) {
            throw new IllegalArgumentException("ai.decision 当前供应商配置不能为空");
        }

        if (selected.getUrl() == null) {
            throw new IllegalArgumentException("ai.decision 当前供应商 url 不能为空");
        }

        Duration timeout = selected.getTimeout();

        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ai.decision 当前供应商 timeout 必须大于 0");
        }

        double threshold = selected.getMinAnswerProbability();

        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("ai.decision 当前供应商 min-answer-probability 必须位于 0 到 1 之间");
        }

        /*
         * 只有功能真正启用并选中 Jev 时才强制检查密钥。
         * 默认 enabled=false 时不会因为空密钥阻止应用启动。
         */
        if (enabled && provider == Provider.JEV && (selected.getApiKey() == null || selected.getApiKey().isBlank())) {
            throw new IllegalArgumentException("启用 Jev 时必须配置 ai.decision.jev.api-key");
        }

        /*
         * Jev 请求要求 model，因此启用时必须配置。
         * Laya 可以省略 model 并使用自己的自动路由。
         */
        if (enabled && provider == Provider.JEV && (selected.getModel() == null || selected.getModel().isBlank())) {
            throw new IllegalArgumentException("启用 Jev 时必须配置 ai.decision.jev.model");
        }
    }
}