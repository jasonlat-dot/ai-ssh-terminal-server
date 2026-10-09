package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * System One 统一外部配置。
 *
 * <p>配置前缀为 {@code ai.decision}。Jev 与 Laya 使用相同的 System One HTTP 协议，
 * 因此只维护一组连接参数。部署时填写哪个服务的 URL、API Key 和模型，
 * 应用就直接调用哪个服务，不再通过 provider 在两套重复配置之间切换。</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.decision")
public class DecisionProviderProperties implements InitializingBean {

    /**
     * 是否启用 System One 结构化决策能力。
     *
     * <p>false 时意图识别、工具筛选和工具结果判断均不会发送外部请求。</p>
     */
    private boolean enabled = false;

    /**
     * 意图识别是否启用影子模式。
     *
     * <p>true 时仍会调用 System One 并记录结果，但不会替代原有 LLM 意图分类流程。</p>
     */
    private boolean shadowMode = true;

    /**
     * 完整的 System One HTTP 接口地址。
     *
     * <p>Jev 可填写托管服务地址，Laya 可填写本地或私有部署地址。</p>
     */
    private URI url = URI.create("https://api.typesafe.ai/v1/systemone");

    /**
     * 可选的 Bearer API Key。
     *
     * <p>Jev 需要有效密钥；未启用鉴权的 Laya 可以保持为空。
     * 应通过环境变量注入真实密钥，不能把密钥提交到版本库。</p>
     */
    private String apiKey = "";

    /**
     * 请求发送的模型名称。
     *
     * <p>Jev 通常使用 {@code jev-latest}；Laya 可使用 {@code multilingual}。
     * 配置为空字符串时，适配器不会发送 model 字段，由服务端自行选择。</p>
     */
    private String model = "jev-latest";

    /**
     * HTTP 建连和完整请求的最大等待时间。
     */
    private Duration timeout = Duration.ofSeconds(3);

    /**
     * 意图识别被选中答案的最低概率。
     *
     * <p>该值对应 {@code probabilities[choice]}，不是供应商原生 confidence。</p>
     */
    private double minAnswerProbability = 0.72;

    /**
     * 工具结果与当前意图偏差判断的独立配置。
     *
     * <p>该能力复用上面的统一 URL、API Key、模型和超时，
     * 只单独控制业务开关、影子模式、概率门槛和结果长度。</p>
     */
    private ToolOutcomeProperties toolOutcome = new ToolOutcomeProperties();

    /**
     * 每轮模型请求工具筛选的独立配置。
     *
     * <p>该能力复用统一 System One 连接，只单独控制筛选门槛、安全保留策略
     * 和发送给外部决策服务的工具信息规模。</p>
     */
    private ToolSelectionProperties toolSelection = new ToolSelectionProperties();

    /**
     * 工具结果与意图偏差判断配置。
     */
    @Data
    public static class ToolOutcomeProperties {

        /**
         * 是否调用 System One 执行工具结果结构化判断。
         */
        private boolean enabled = true;

        /**
         * 是否只观测工具结果判断而不影响反馈回路。
         */
        private boolean shadowMode = false;

        /**
         * 被选中工具结果结论的最低使用概率。
         */
        private double minAnswerProbability = 0.78;

        /**
         * 单次发送给 System One 的工具结果最大字符数。
         */
        private int maxResultCharacters = 4_000;
    }

    /**
     * 每轮工具筛选配置。
     */
    @Data
    public static class ToolSelectionProperties {

        /** 是否调用 System One 为当前模型请求筛选工具。 */
        private boolean enabled = true;

        /** true 时只记录建议，不真正删除 LlmRequest 中的工具。 */
        private boolean shadowMode = false;

        /** USE/SKIP 结论可以正式生效的最低答案概率。 */
        private double minAnswerProbability = 0.78;

        /** 即使所有工具都被判定 SKIP，也至少保留的工具数量。 */
        private int minRetainedTools = 1;

        /** 单次 System One 请求最多判断的工具数量，溢出部分自动保留。 */
        private int maxToolsPerRequest = 32;

        /** 每个工具说明允许发送的最大字符数。 */
        private int maxDescriptionCharacters = 600;

        /** 最近模型调用安全摘要允许发送的最大字符数。 */
        private int maxContextCharacters = 2_000;

        /** 永远保留且不交给 System One 判断的工具名称。 */
        private List<String> alwaysKeepTools = new ArrayList<>();
    }

    /**
     * Spring 完成属性绑定后校验统一连接参数和各业务能力参数。
     *
     * <p>API Key 和模型允许为空，因为未启用鉴权的 Laya 不需要 API Key，
     * 且部分 Laya 部署允许省略模型并自动路由。供应商侧的必填要求由对应服务响应。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (url == null) {
            throw new IllegalArgumentException("ai.decision.url 不能为空");
        }

        String scheme = url.getScheme();

        if (!url.isAbsolute()
                || scheme == null
                || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("ai.decision.url 必须是绝对 HTTP/HTTPS 地址");
        }

        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ai.decision.timeout 必须大于 0");
        }

        if (!Double.isFinite(minAnswerProbability)
                || minAnswerProbability < 0.0
                || minAnswerProbability > 1.0) {
            throw new IllegalArgumentException("ai.decision.min-answer-probability 必须位于 0 到 1 之间");
        }

        if (toolOutcome == null) {
            throw new IllegalArgumentException("ai.decision.tool-outcome 配置不能为空");
        }

        double toolOutcomeThreshold = toolOutcome.getMinAnswerProbability();

        if (!Double.isFinite(toolOutcomeThreshold)
                || toolOutcomeThreshold < 0.0
                || toolOutcomeThreshold > 1.0) {
            throw new IllegalArgumentException("ai.decision.tool-outcome.min-answer-probability 必须位于 0 到 1 之间");
        }

        if (toolOutcome.getMaxResultCharacters() < 256) {
            throw new IllegalArgumentException("ai.decision.tool-outcome.max-result-characters 不能小于 256");
        }

        if (toolSelection == null) {
            throw new IllegalArgumentException("ai.decision.tool-selection 配置不能为空");
        }

        double toolSelectionThreshold = toolSelection.getMinAnswerProbability();

        if (!Double.isFinite(toolSelectionThreshold)
                || toolSelectionThreshold < 0.0
                || toolSelectionThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "ai.decision.tool-selection.min-answer-probability 必须位于 0 到 1 之间");
        }

        if (toolSelection.getMinRetainedTools() < 1) {
            throw new IllegalArgumentException(
                    "ai.decision.tool-selection.min-retained-tools 不能小于 1");
        }

        if (toolSelection.getMaxToolsPerRequest() < 1) {
            throw new IllegalArgumentException(
                    "ai.decision.tool-selection.max-tools-per-request 不能小于 1");
        }

        if (toolSelection.getMaxDescriptionCharacters() < 64) {
            throw new IllegalArgumentException(
                    "ai.decision.tool-selection.max-description-characters 不能小于 64");
        }

        if (toolSelection.getMaxContextCharacters() < 256) {
            throw new IllegalArgumentException(
                    "ai.decision.tool-selection.max-context-characters 不能小于 256");
        }

        if (toolSelection.getAlwaysKeepTools() == null) {
            toolSelection.setAlwaysKeepTools(new ArrayList<>());
        }
    }
}
