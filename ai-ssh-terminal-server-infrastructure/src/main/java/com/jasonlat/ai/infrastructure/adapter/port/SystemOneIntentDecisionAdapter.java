package com.jasonlat.ai.infrastructure.adapter.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jasonlat.ai.domain.agent.adapter.port.IIntentDecisionPort;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Jev/Laya System One HTTP 协议的意图决策适配器。
 *
 * <p>Jev 和 Laya 都支持 {@code POST /v1/systemone}，
 * 因此这里只维护一套 HTTP 客户端，通过配置切换供应商。</p>
 *
 * <p>该适配器采用 fail-open 策略：任何远程调用失败、响应异常或低置信度，
 * 都返回 Optional.empty()，让领域层继续使用现有 LLM 分类器。</p>
 */
@Slf4j
@Component
public class SystemOneIntentDecisionAdapter implements IIntentDecisionPort {

    /**
     * 请求中的问题 ID。
     *
     * <p>System One 会使用相同 ID 把答案放在 answers.intent 中。</p>
     */
    private static final String QUESTION_ID = "intent";

    /**
     * 发送给 System One 的意图选择说明。
     *
     * <p>要求模型只选择一个主意图；复合任务通过 COMPOUND 表达，
     * 信息不足时通过 UNKNOWN 表达。</p>
     */
    private static final String QUESTION_INSTRUCTIONS = """
            判断 state.current_user_message 的主要 SSH 运维意图。
            state.conversation_context 只能作为辅助上下文。
            如果消息同时包含两个及以上明确任务，选择 COMPOUND。
            如果消息缺少足够信息或不属于任何候选类型，选择 UNKNOWN。
            只选择一个最符合的候选项。
            """;

    /**
     * 当前选中的供应商运行参数。
     */
    private final SystemOneDecisionSettings settings;

    /**
     * Spring Boot 提供的 JSON 序列化器。
     */
    private final ObjectMapper objectMapper;

    /**
     * Java 标准库 HTTP 客户端。
     *
     * <p>客户端在 Bean 构造时创建一次，后续请求复用连接池。</p>
     */
    private final HttpClient httpClient;

    /**
     * 创建 System One 意图决策适配器。
     *
     * @param settings     已解析并校验过的供应商运行参数
     * @param objectMapper Spring Boot 统一管理的 ObjectMapper
     */
    public SystemOneIntentDecisionAdapter(
            SystemOneDecisionSettings settings,
            ObjectMapper objectMapper
    ) {
        this.settings = settings;
        this.objectMapper = objectMapper;

        /*
         * connectTimeout 控制建立 TCP/TLS 连接的最长等待时间。
         * 每个请求还会单独设置 request timeout。
         */
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 调用 Jev 或 Laya 完成结构化意图分类。
     *
     * @param request 已校验的领域层意图分类请求
     * @return 达到本地使用门槛时返回结果，否则返回 Optional.empty()
     */
    @Override
    public Optional<IntentDecision> classify(IntentDecisionRequest request) {
        /*
         * 未启用时直接退出，不产生网络请求，
         * 原有 LLM 意图分类链路完全不受影响。
         */
        if (!settings.enabled()) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();

        try {
            // 把领域请求转换为 Jev/Laya 共用的 System One JSON 协议。
            String requestBody = objectMapper.writeValueAsString(buildRequestBody(request));

            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(settings.endpoint())
                            .timeout(settings.timeout())
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

            /*
             * Jev 必须发送 Bearer Token。
             * Laya 未配置 LAYA_API_KEY 时不发送 Authorization 头。
             */
            if (!settings.apiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + settings.apiKey());
            }

            /*
             * 当前分类节点本身是同步链路，因此这里使用同步 send。
             * 严格的 timeout 和 fail-open 降级可防止远程服务长期阻塞主流程。
             */
            HttpResponse<String> response =
                    httpClient.send(requestBuilder.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            /*
             * 401、422、429、500、503 等状态都不直接抛给业务层，
             * 而是返回 empty，让现有 LLM 接管。
             */
            if (response.statusCode() / 100 != 2) {
                log.warn("System One 意图分类调用失败 provider={} status={} elapsedMs={}",
                        providerName(), response.statusCode(), elapsedMillis);

                return Optional.empty();
            }

            Optional<IntentDecision> parsed = parseResponse(response.body());

            if (parsed.isEmpty()) {
                log.warn("System One 返回了无法识别的意图分类结构 provider={} elapsedMs={}",
                        providerName(), elapsedMillis);

                return Optional.empty();
            }

            IntentDecision decision = parsed.get();

            /*
             * 使用被选中答案的概率作为统一门槛。
             * 不使用 Jev/Laya 各自的 native confidence，
             * 因为两者的 confidence 计算公式不同。
             */
            boolean accepted = decision.answerProbability() >= settings.minAnswerProbability();

            log.info(
                    "System One 意图分类 provider={} model={} intent={} answerProbability={} nativeConfidence={} accepted={} shadowMode={} elapsedMs={}",
                    decision.provider(),
                    decision.model(),
                    decision.intent(),
                    decision.answerProbability(),
                    decision.nativeConfidence(),
                    accepted,
                    settings.shadowMode(),
                    elapsedMillis);

            /*
             * Shadow mode 只调用、解析和记录结果，
             * 不把结果交给领域层使用。
             */
            if (settings.shadowMode()) {
                return Optional.empty();
            }

            /*
             * 未达到本地概率门槛时，让原有 LLM 重新分类，
             * 避免低质量快速决策覆盖当前逻辑。
             */
            if (!accepted) {
                return Optional.empty();
            }

            return Optional.of(decision);
        } catch (InterruptedException exception) {
            /*
             * 恢复线程中断标记，不能把上层的取消信号吞掉。
             */
            Thread.currentThread().interrupt();

            log.warn("System One 意图分类被中断 provider={}", providerName());

            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            /*
             * 不记录请求正文和 API Key。
             * 用户消息可能包含服务器地址、命令或其他敏感信息。
             */
            log.warn(
                    "System One 意图分类异常，回退原有 LLM provider={} exception={} message={} elapsedMs={}",
                    providerName(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    elapsedMillis);

            // 完整堆栈只进入 DEBUG 日志，避免普通日志过于嘈杂。
            log.debug("System One 意图分类异常详情", exception);

            return Optional.empty();
        }
    }

    /**
     * 构建 Jev/Laya 共用的 System One 请求体。
     *
     * <p>请求格式：</p>
     *
     * <pre>
     * {
     *   "model": "multilingual",
     *   "state": {
     *     "current_user_message": "用户消息",
     *     "conversation_context": "对话上下文"
     *   },
     *   "questions": {
     *     "intent": {
     *       "type": "choice",
     *       "instructions": "分类说明",
     *       "criteria": {
     *         "DIAGNOSE": "排障并定位故障原因"
     *       }
     *     }
     *   }
     * }
     * </pre>
     *
     * @param request 领域层传入的意图分类请求
     * @return 可以直接序列化的 Jackson ObjectNode
     */
    private ObjectNode buildRequestBody(IntentDecisionRequest request) {
        ObjectNode root = objectMapper.createObjectNode();

        /*
         * Jev 要求 model。
         * Laya 允许省略 model 并自动路由，因此空模型时不写该字段。
         */
        if (!settings.model().isBlank()) {
            root.put("model", settings.model());
        }

        /*
         * 使用结构化 state，而不是把上下文拼进一大段字符串。
         * Jev/Laya 都支持对象类型的 state。
         */
        ObjectNode state = root.putObject("state");
        state.put("current_user_message", request.message());
        state.put("conversation_context", request.context());

        // 创建 questions.intent Choice 问题。
        ObjectNode questions = root.putObject("questions");

        ObjectNode intentQuestion = questions.putObject(QUESTION_ID);

        intentQuestion.put("type", "choice");

        intentQuestion.put("instructions", QUESTION_INSTRUCTIONS);

        /*
         * criteria 的 key 是最终返回的 choice，
         * value 是该候选意图的简短判定说明。
         */
        ObjectNode criteria = intentQuestion.putObject("criteria");

        request.criteria()
                .forEach((intent, description) ->
                        criteria.put(intent.name(), description));

        return root;
    }

    /**
     * 解析 Jev/Laya Choice 响应。
     *
     * <p>只读取两者公共协议中的字段：</p>
     *
     * <ul>
     *     <li>model</li>
     *     <li>answers.intent.type</li>
     *     <li>answers.intent.choice</li>
     *     <li>answers.intent.probabilities</li>
     *     <li>answers.intent.confidence</li>
     * </ul>
     *
     * @param responseBody 供应商返回的原始 JSON 字符串
     * @return 响应完整且意图合法时返回领域结果，否则返回 Optional.empty()
     * @throws IOException JSON 解析失败时抛出，由 classify 方法统一降级处理
     */
    private Optional<IntentDecision> parseResponse(String responseBody) throws IOException {
        /*
         * {
         *   "model" : "jev-1.13.0",
         *   "answers" : {
         *     "intent" : {
         *       "type" : "choice",
         *       "choice" : "MONITOR",
         *       "confidence" : 0.99,
         *       "probabilities" : {
         *         "UNKNOWN" : 0.0,
         *         "COMPOUND" : 0.0,
         *         "EXPLAIN" : 0.0,
         *         "MONITOR" : 0.99,
         *         "DEPLOY" : 0.0,
         *         "BACKUP" : 0.0,
         *         "CONTINUE" : 0.0,
         *         "CHAT" : 0.0,
         *         "SECURITY" : 0.0,
         *         "EXECUTE" : 0.01,
         *         "CONFIGURE" : 0.0,
         *         "SEARCH" : 0.0,
         *         "DIAGNOSE" : 0.0
         *       }
         *     }
         *   },
         *   "usage" : {
         *     "input_tokens" : 705,
         *     "output_tokens" : 127
         *   }
         * }
         */
        JsonNode root = objectMapper.readTree(responseBody);

        // System One 会按照问题 ID 把答案放到 answers.intent。
        JsonNode answer = root.path("answers").path(QUESTION_ID);

        if (!answer.isObject()) {
            return Optional.empty();
        }

        // 当前适配器只接受 choice 类型答案。
        if (!"choice".equalsIgnoreCase(answer.path("type").asText())) {
            return Optional.empty();
        }

        String rawChoice = answer.path("choice").asText("");

        IntentTypeEnumVO intent;

        try {
            /*
             * 供应商返回的是 criteria 中的 key，
             * 将其转成系统现有 IntentTypeEnumVO。
             */
            intent = IntentTypeEnumVO.valueOf(rawChoice.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // 返回未知枚举时不能继续参与业务路由。
            return Optional.empty();
        }

        JsonNode probabilitiesNode = answer.path("probabilities");

        if (!probabilitiesNode.isObject()) {
            return Optional.empty();
        }

        Map<IntentTypeEnumVO, Double> probabilities = new LinkedHashMap<>();

        probabilitiesNode.properties().forEach(entry -> {
            try {
                IntentTypeEnumVO candidate = IntentTypeEnumVO.valueOf(entry.getKey().toUpperCase(Locale.ROOT));

                double probability = entry.getValue().asDouble(Double.NaN);

                /*
                 * 只保留系统认识且位于 0 到 1 之间的概率。
                 * 非数字、Infinity 和越界值都会被忽略。
                 */
                if (Double.isFinite(probability) && probability >= 0.0 && probability <= 1.0) {
                    probabilities.put(candidate, probability);
                }
            } catch (IllegalArgumentException ignored) {
                /*
                 * 供应商如果返回了新选项，旧客户端不能直接使用，
                 * 但也不需要让整个请求因为一个未知候选项失败。
                 */
                log.debug("忽略 System One 返回的未知意图选项 option={}", entry.getKey());
            }
        });

        /*
         * Choice 契约要求 choice 必须出现在 probabilities 中。
         * 缺少该项说明响应不完整，必须回退原有 LLM。
         */
        Double answerProbability = probabilities.get(intent);

        if (answerProbability == null) {
            return Optional.empty();
        }

        /*
         * nativeConfidence 仅用于日志和后续校准分析。
         *
         * Jev 的 Choice confidence 使用最高概率和选项数计算；
         * Laya 默认使用归一化熵。
         * 两者不能共用同一个原生 confidence 阈值。
         */
        double nativeConfidence = answer.path("confidence").asDouble(Double.NaN);

        /*
         * 优先记录服务端实际返回的模型名。
         * 服务端未返回时使用请求配置的模型名。
         */
        String model = root.path("model").asText(settings.model());

        return Optional.of(
                new IntentDecision(
                        intent,
                        answerProbability,
                        nativeConfidence,
                        probabilities,
                        providerName(),
                        model,
                        responseBody));
    }

    /**
     * 获取用于日志和领域结果的供应商名称。
     *
     * @return 小写形式的 jev 或 laya
     */
    private String providerName() {
        return settings.provider().name().toLowerCase(Locale.ROOT);
    }
}