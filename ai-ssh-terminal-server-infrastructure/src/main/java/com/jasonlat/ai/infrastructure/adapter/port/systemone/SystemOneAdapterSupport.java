package com.jasonlat.ai.infrastructure.adapter.port.systemone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jasonlat.ai.infrastructure.model.settings.SystemOneDecisionSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * System One 适配器公共协议支持。
 *
 * <p>意图识别、工具结果判断以及后续可能增加的记忆筛选等能力，
 * 都使用相同的 System One HTTP 和 Choice 响应协议。本类统一处理：</p>
 * <ul>
 *     <li>HTTP 客户端创建和连接复用；</li>
 *     <li>model、Content-Type、Accept 和 Bearer Token；</li>
 *     <li>请求超时、非 2xx 响应以及 fail-open 异常降级；</li>
 *     <li>answers.&lt;questionId&gt; Choice 公共结构解析；</li>
 *     <li>通用 Choice 问题构建与安全文本截断。</li>
 * </ul>
 *
 * <p>该类不认识任何领域枚举，也不决定业务阈值和影子模式。
 * 子类负责把字符串 choice 映射成自己的领域结果，并决定是否正式采用。</p>
 */
@Slf4j
public abstract class SystemOneAdapterSupport {

    /**
     * System One 统一连接参数。
     */
    protected final SystemOneDecisionSettings settings;

    /**
     * Spring Boot 统一管理的 JSON 序列化器。
     */
    protected final ObjectMapper objectMapper;

    /**
     * 所有请求共享的 Java 标准 HTTP 客户端和连接池。
     */
    private final HttpClient httpClient;

    /**
     * 创建公共 System One 协议支持。
     *
     * @param settings     已解析和校验的 System One 连接参数
     * @param objectMapper Spring Boot 统一管理的 ObjectMapper
     */
    protected SystemOneAdapterSupport(
            SystemOneDecisionSettings settings,
            ObjectMapper objectMapper
    ) {
        this.settings = settings;
        this.objectMapper = objectMapper;

        /*
         * HttpClient 在适配器 Bean 创建时只初始化一次，后续请求复用连接池。
         * connectTimeout 控制 TCP/TLS 建连，每个请求还会设置完整请求 timeout。
         */
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 创建包含可选 model 字段的 System One 请求根节点。
     *
     * <p>Jev 通常要求 model；Laya 可以在 model 为空时由服务端自动路由。</p>
     *
     * @return 可继续写入 state 和 questions 的请求根节点
     */
    protected final ObjectNode createRequestRoot() {
        ObjectNode root = objectMapper.createObjectNode();

        if (!settings.model().isBlank()) {
            root.put("model", settings.model());
        }

        return root;
    }

    /**
     * 向请求根节点添加一个枚举 Choice 问题。
     *
     * <p>枚举名称作为 criteria 的 key，描述作为 value。System One 返回的 choice
     * 可以由具体适配器直接映射回对应领域枚举。</p>
     *
     * @param root         System One 请求根节点
     * @param questionId   问题唯一 ID，同时也是响应 answers 下的字段名
     * @param instructions 本次 Choice 的完整判断说明
     * @param criteria     领域枚举及其语义描述
     * @param <E>          具体适配器使用的枚举类型
     */
    protected final <E extends Enum<E>> void addChoiceQuestion(
            ObjectNode root,
            String questionId,
            String instructions,
            Map<E, String> criteria
    ) {
        /*
         * 一个 System One 请求可以同时包含多个 Choice 问题。不能每次都直接
         * root.putObject("questions")，否则后添加的问题会覆盖前面的所有问题。
         */
        ObjectNode questions;

        if (root.path("questions").isObject()) {
            questions = (ObjectNode) root.path("questions");
        } else {
            questions = root.putObject("questions");
        }

        ObjectNode question = questions.putObject(questionId);
        question.put("type", "choice");
        question.put("instructions", instructions);

        ObjectNode criteriaNode = question.putObject("criteria");

        criteria.forEach((candidate, description) ->
                criteriaNode.put(candidate.name(), description));
    }

    /**
     * 发送 System One 请求并解析公共 Choice 响应。
     *
     * <p>任何网络异常、线程中断、非 2xx 状态或非法 Choice 结构都会返回空，
     * 具体适配器收到空值后继续执行原有降级流程。本方法不会记录请求正文和密钥。</p>
     *
     * @param requestRoot  已构建完成的 System One JSON 请求
     * @param questionId   需要从 answers 中读取的问题 ID
     * @param operation    日志中的业务操作名，例如“意图分类”
     * @param fallbackText 异常日志中的降级说明，例如“回退原有 LLM”
     * @return 成功发送并解析时返回公共 Choice 响应，否则返回 Optional.empty()
     */
    protected final Optional<ChoiceResponse> executeChoice(
            ObjectNode requestRoot,
            String questionId,
            String operation,
            String fallbackText
    ) {
        return executeChoices(
                requestRoot,
                List.of(questionId),
                operation,
                fallbackText)
                .map(responses -> responses.get(questionId));
    }

    /**
     * 用一次 HTTP 请求发送并解析多个 System One Choice 问题。
     *
     * <p>工具筛选会为每个候选工具创建一个 USE/SKIP 问题。如果任意问题缺失、
     * 结构非法或概率不完整，本方法整体返回空，让调用方保留全部工具。</p>
     *
     * @param requestRoot  已构建完成且包含多个 questions 的请求根节点
     * @param questionIds  期望从 answers 中读取的全部问题 ID
     * @param operation    日志中的业务操作名
     * @param fallbackText 失败时的降级说明
     * @return 按 questionIds 顺序保存的 Choice 响应 Map；失败时返回空
     */
    protected final Optional<Map<String, ChoiceResponse>> executeChoices(
            ObjectNode requestRoot,
            Collection<String> questionIds,
            String operation,
            String fallbackText) {
        long startedAt = System.nanoTime();

        try {
            Set<String> distinctQuestionIds = new LinkedHashSet<>(questionIds);

            if (distinctQuestionIds.isEmpty()
                    || distinctQuestionIds.size() != questionIds.size()
                    || distinctQuestionIds.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("System One 问题 ID 不能为空或重复");
            }

            /*
             * {
             *   "model": "multilingual",
             *   "state": {
             *     "user_message": "查看服务器磁盘空间",
             *     "recent_context": "user: 查看服务器磁盘空间",
             *     "agent_name": "SshAgent",
             *     "current_intent": "EXECUTE",
             *     "candidate_tool_count": 3
             *   },
             *   "questions": {
             *     "tool_0": {
             *       "type": "choice",
             *       "instructions": "判断下面这个工具是否应该出现在当前 Agent 的本轮模型请求中。\n工具名称：executeCommand\n工具说明：在远程服务器执行 Shell 命令\n请结合 state.user_message、state.recent_context、state.current_intent 和当前 Agent 判断。\n只在工具与当前任务及其合理后续步骤明显无关时选择 SKIP；不确定时选择 USE。\n",
             *       "criteria": {
             *         "USE": "当前任务、当前步骤或合理的后续步骤可能需要该工具；存在不确定性时也选择 USE",
             *         "SKIP": "该工具与当前任务和合理后续步骤明显无关，本轮隐藏不会阻碍任务完成"
             *       }
             *     },
             *     "tool_1": {
             *       "type": "choice",
             *       "instructions": "判断下面这个工具是否应该出现在当前 Agent 的本轮模型请求中。\n工具名称：readFile\n工具说明：读取远程服务器上的文件\n请结合 state.user_message、state.recent_context、state.current_intent 和当前 Agent 判断。\n只在工具与当前任务及其合理后续步骤明显无关时选择 SKIP；不确定时选择 USE。\n",
             *       "criteria": {
             *         "USE": "当前任务、当前步骤或合理的后续步骤可能需要该工具；存在不确定性时也选择 USE",
             *         "SKIP": "该工具与当前任务和合理后续步骤明显无关，本轮隐藏不会阻碍任务完成"
             *       }
             *     },
             *     "tool_2": {
             *       "type": "choice",
             *       "instructions": "判断下面这个工具是否应该出现在当前 Agent 的本轮模型请求中。\n工具名称：uploadFile\n工具说明：向远程服务器上传文件\n请结合 state.user_message、state.recent_context、state.current_intent 和当前 Agent 判断。\n只在工具与当前任务及其合理后续步骤明显无关时选择 SKIP；不确定时选择 USE。\n",
             *       "criteria": {
             *         "USE": "当前任务、当前步骤或合理的后续步骤可能需要该工具；存在不确定性时也选择 USE",
             *         "SKIP": "该工具与当前任务和合理后续步骤明显无关，本轮隐藏不会阻碍任务完成"
             *       }
             *     }
             *   }
             * }
             */
            String requestBody = objectMapper.writeValueAsString(requestRoot);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(settings.endpoint())
                    .timeout(settings.timeout())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

            /*
             * 配置了 api-key 时统一发送 Bearer Token。
             * 未启用鉴权的 Laya 可以保持为空，此时不添加 Authorization 请求头。
             */
            if (!settings.apiKey().isBlank()) {
                requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
            }

            /*
             * {
             *   "model": "jev-1.13.0",
             *   "answers": {
             *     "tool_0": {
             *       "type": "choice",
             *       "choice": "USE",
             *       "confidence": 0.97,
             *       "probabilities": {
             *         "USE": 0.97,
             *         "SKIP": 0.03
             *       }
             *     },
             *     "tool_1": {
             *       "type": "choice",
             *       "choice": "SKIP",
             *       "confidence": 0.91,
             *       "probabilities": {
             *         "USE": 0.09,
             *         "SKIP": 0.91
             *       }
             *     },
             *     "tool_2": {
             *       "type": "choice",
             *       "choice": "SKIP",
             *       "confidence": 0.96,
             *       "probabilities": {
             *         "USE": 0.04,
             *         "SKIP": 0.96
             *       }
             *     }
             *   }
             * }
             */
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long elapsedMillis = elapsedMillis(startedAt);

            if (response.statusCode() / 100 != 2) {
                log.warn("System One {}调用失败 status={} provider={} elapsedMs={}",
                        operation, response.statusCode(), providerName(), elapsedMillis);

                return Optional.empty();
            }

            Optional<Map<String, ChoiceResponse>> parsed = parseChoiceResponses(
                    response.body(),
                    distinctQuestionIds,
                    elapsedMillis);

            if (parsed.isEmpty()) {
                log.warn("System One 返回了无法识别的{}结构 provider={} elapsedMs={}",
                        operation, providerName(), elapsedMillis);
            }

            return parsed;
        } catch (InterruptedException exception) {
            // 恢复线程中断标记，不能吞掉上层取消信号。
            Thread.currentThread().interrupt();

            log.warn("System One {}被中断 provider={}", operation, providerName());

            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            long elapsedMillis = elapsedMillis(startedAt);

            /*
             * 普通日志只记录异常类型和消息，绝不输出请求正文、命令、工具结果或 API Key。
             */
            log.warn(
                    "System One {}异常，{} exception={} message={} provider={} elapsedMs={}",
                    operation,
                    fallbackText,
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    providerName(),
                    elapsedMillis);

            log.debug("System One {} 异常详情", operation, exception);

            return Optional.empty();
        }
    }

    /**
     * 解析所有 System One Choice 能力共享的响应字段。
     *
     * @param responseBody 原始 JSON 响应
     * @param questionIds  本次批量请求中需要读取的全部问题 ID
     * @param elapsedMillis 从发送请求到收到响应的耗时
     * @return 按问题 ID 保存的 choice、概率分布、原生 confidence、模型和原始响应
     * @throws IOException JSON 语法非法时抛出，由 executeChoices 统一降级
     */
    private Optional<Map<String, ChoiceResponse>> parseChoiceResponses(
            String responseBody,
            Collection<String> questionIds,
            long elapsedMillis
    ) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        Map<String, ChoiceResponse> responses = new LinkedHashMap<>();

        for (String questionId : questionIds) {
            Optional<ChoiceResponse> response = parseChoiceAnswer(
                    root,
                    responseBody,
                    questionId,
                    elapsedMillis);

            /*
             * 多问题决策必须完整。如果任意工具没有合法答案，整体 fail-open，
             * 避免只过滤成功返回的部分工具产生不可预测能力缺失。
             */
            if (response.isEmpty()) {
                return Optional.empty();
            }

            responses.put(questionId, response.get());
        }

        return Optional.of(Collections.unmodifiableMap(responses));
    }

    /**
     * 从已经解析的响应根节点读取一个 Choice 答案。
     *
     * @param root           System One 响应根节点
     * @param responseBody   原始 JSON 响应
     * @param questionId     当前问题 ID
     * @param elapsedMillis  整个 HTTP 请求耗时
     * @return 单个完整 Choice 响应；结构或概率非法时返回空
     */
    private Optional<ChoiceResponse> parseChoiceAnswer(
            JsonNode root,
            String responseBody,
            String questionId,
            long elapsedMillis
    ) {
        JsonNode answer = root.path("answers").path(questionId);

        if (!answer.isObject()
                || !"choice".equalsIgnoreCase(answer.path("type").asText())) {
            return Optional.empty();
        }

        String choice = answer.path("choice").asText("");

        if (choice.isBlank()) {
            return Optional.empty();
        }

        JsonNode probabilitiesNode = answer.path("probabilities");

        if (!probabilitiesNode.isObject()) {
            return Optional.empty();
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();

        probabilitiesNode.properties().forEach(entry -> {
            double probability = entry.getValue().asDouble(Double.NaN);

            // 只保留有限且位于 0 到 1 之间的合法概率。
            if (Double.isFinite(probability) && probability >= 0.0 && probability <= 1.0) {
                probabilities.put(entry.getKey(), probability);
            }
        });

        /*
         * 正常情况下 choice 与 probabilities 的 key 完全一致。
         * 为兼容供应商偶尔返回不同大小写的候选名，精确匹配失败后再执行
         * 一次大小写不敏感匹配；概率 Map 本身仍保留服务端原始 key。
         */
        Double answerProbability = probabilities.get(choice);

        if (answerProbability == null) {
            answerProbability = probabilities.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(choice))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (answerProbability == null) {
            return Optional.empty();
        }

        double nativeConfidence = answer.path("confidence").asDouble(Double.NaN);
        String model = root.path("model").asText(settings.model());

        return Optional.of(new ChoiceResponse(
                choice,
                answerProbability,
                nativeConfidence,
                probabilities,
                model,
                responseBody,
                elapsedMillis));
    }

    /**
     * System One Choice 响应的协议级结果。
     *
     * @param choice             服务端选中的 criteria key
     * @param answerProbability  probabilities[choice] 对应概率
     * @param nativeConfidence   供应商原始 confidence，仅用于观测
     * @param probabilities      未映射领域枚举的完整字符串概率分布
     * @param model              服务端实际模型名
     * @param rawResponse        原始 JSON 响应
     * @param elapsedMillis      本次 HTTP 调用耗时
     */
    protected record ChoiceResponse(
            String choice,
            double answerProbability,
            double nativeConfidence,
            Map<String, Double> probabilities,
            String model,
            String rawResponse,
            long elapsedMillis
    ) {

        /**
         * 对概率分布执行防御性复制，避免具体适配器意外修改公共解析结果。
         */
        protected ChoiceResponse {
            probabilities = probabilities == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }
    }

    /**
     * 对普通文本执行从开头截断。
     *
     * @param value     可空原始文本
     * @param maxLength 最大保留字符数
     * @return 不超过 maxLength 的文本
     */
    protected static String truncate(String value, int maxLength) {
        String normalized = value == null ? "" : value;

        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    /**
     * 对长工具结果执行首尾保留截断。
     *
     * <p>终端输出开头通常包含执行上下文，结尾通常包含最终状态或错误原因。</p>
     *
     * @param value     可空工具结果
     * @param maxLength 最大保留字符数
     * @return 原文或“开头 + 截断标记 + 结尾”
     */
    protected static String truncateHeadAndTail(String value, int maxLength) {
        String normalized = value == null ? "" : value;

        if (normalized.length() <= maxLength) {
            return normalized;
        }

        String marker = "\n...[工具结果中间内容已截断]...\n";
        int availableCharacters = maxLength - marker.length();
        int headLength = availableCharacters / 2;
        int tailLength = availableCharacters - headLength;

        return normalized.substring(0, headLength)
                + marker
                + normalized.substring(normalized.length() - tailLength);
    }

    /**
     * 获取统一协议名称。
     *
     * <p>实际调用 Jev 还是 Laya 完全由 endpoint 决定，不通过 URL 猜测供应商。</p>
     */
    protected final String providerName() {
        return "system-one";
    }

    /**
     * 计算从指定开始时间到当前的毫秒数。
     */
    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
