package com.jasonlat.ai.domain.agent.service.intent.classifier.node;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jasonlat.ai.domain.agent.adapter.port.IIntentDecisionPort;
import com.jasonlat.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.service.intent.ContextTracker;
import com.jasonlat.ai.domain.agent.service.intent.IntentService;
import com.jasonlat.ai.domain.agent.service.intent.classifier.AbstractIntentClassifierSupport;
import com.jasonlat.ai.domain.agent.service.intent.classifier.factory.DefaultClassifyFactory;
import com.jasonlat.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 第二层意图分类节点。
 *
 * <p>当规则分类器置信度不足时，本节点按以下顺序执行：</p>
 *
 * <ol>
 *     <li>优先尝试 Jev/Laya System One 快速结构化分类；</li>
 *     <li>System One 未启用、处于影子模式、调用失败或置信度不足时，
 *         回退到原有 Spring AI ChatModel 分类；</li>
 *     <li>两级分类都无法得到可靠结果时返回 UNKNOWN，
 *         由主 Agent 自行理解用户输入。</li>
 * </ol>
 *
 * <p>原有 ChatModel 继续复用 Agent 自己的 OpenAiApi 和模型配置，
 * 但不会挂载 ToolCallback，因此意图分类过程不会触发工具调用。</p>
 *
 * @see IntentService
 */
@Slf4j
@Component("llMIntentClassifierNode")
public class LLMIntentClassifierNode extends AbstractIntentClassifierSupport {

    /**
     * 会话上下文管理器。
     *
     * <p>用于读取最近意图、当前任务状态和连续失败信息。</p>
     */
    @Resource
    private ContextTracker contextTracker;
    /**
     * 原有 LLM 意图分类模型。
     *
     * <p>使用 volatile 确保配置线程更新后，分类线程能够立即看到新实例。</p>
     */
    private volatile ChatModel chatModel;
    /**
     * 当前 ChatModel 使用的 OpenAI API 配置。
     */
    private volatile OpenAiApi openAiApi;
    /**
     * 当前 ChatModel 使用的模型名。
     */
    private volatile String modelName;
    /**
     * 原有 LLM JSON 响应解析器。
     *
     * <p>这里只处理简单 JSON，不需要额外 Spring 配置，因此保留独立实例。</p>
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 快速结构化意图决策端口。
     *
     * <p>实际实现由 infrastructure 模块提供，可以切换 Jev 或 Laya。</p>
     */
    @Resource
    private IIntentDecisionPort intentDecisionPort;

    /**
     * System One 可选择的全部意图和语义说明。
     *
     * <p>使用固定、不可修改的 Map，避免每次请求重复创建候选项，
     * 也避免运行过程中候选定义被修改。</p>
     */
    private static final Map<IntentTypeEnumVO, String> SYSTEM_ONE_INTENT_CRITERIA = buildSystemOneIntentCriteria();

    /**
     * 使用 Agent 当前 API 配置创建独立的意图分类 ChatModel。
     *
     * <p>只有 API 对象或模型名变化时才重建，避免每次用户输入都创建模型实例。</p>
     *
     * @param openAiApi Agent 装配链路创建的 OpenAiApi
     * @param modelName Agent 当前使用的模型名
     */
    public synchronized void configure(OpenAiApi openAiApi, String modelName) {
        /*
         * Agent 尚未完成装配时不创建 ChatModel。
         * System One 仍可独立运行；需要回退 LLM 时会返回 UNKNOWN。
         */
        if (openAiApi == null || modelName == null || modelName.isBlank()) {
            return;
        }

        /*
         * API 配置和模型都没有变化时复用当前 ChatModel，
         * 减少重复对象创建。
         */
        if (openAiApi.equals(this.openAiApi) && modelName.equals(this.modelName) && this.chatModel != null) {
            return;
        }

        this.openAiApi = openAiApi;
        this.modelName = modelName;

        /*
         * 该模型不配置工具回调，只负责纯文本 JSON 分类，
         * 不会在意图识别阶段执行 SSH 或其他工具。
         */
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .model(modelName)
                                .build())
                .build();
    }

    /**
     * 原有 LLM 分类提示词。
     *
     * <p>System One 不可用时继续使用该提示词，不改变当前系统的降级行为。</p>
     */
    private static final String CLASSIFY_PROMPT_TEMPLATE = """
            你是一个 SSH 运维场景的意图识别系统。分析用户输入，返回 JSON 格式的意图分类结果。
            
            ## 意图类型
            - DIAGNOSE: 诊断问题（服务挂了、报错、异常排查）
            - CONFIGURE: 配置修改（改配置文件、调参数）
            - DEPLOY: 部署操作（部署、发布、回滚）
            - MONITOR: 监控查看（看日志、查状态、看资源使用）
            - SECURITY: 安全相关（防火墙、权限、证书）
            - BACKUP: 备份恢复（备份数据、恢复数据）
            - EXECUTE: 直接执行（帮我跑某命令）
            - COMPOUND: 复合指令（同时包含多个意图，如"看下502是不是因为改了配置"）
            - EXPLAIN: 解释说明（这个命令什么意思）
            - SEARCH: 搜索查找（找文件、查进程）
            - CHAT: 闲聊
            - CONTINUE: 继续上一个任务
            - UNKNOWN: 无法判断，无法判断时请返回 UNKNOWN 并将 confidence 设为 0
            
            ## 输出格式（仅返回 JSON，无其他内容）
            {"intent":"类型","confidence":0.0-1.0,"entities":{"key":"value"},"candidates":["次选意图1","次选意图2"]}
            当输入明显跨多个意图时 intent 返回 COMPOUND，candidates 列出涉及的具体意图（按可能性排序）。
            
            ## 示例
            输入: "nginx 502了，帮我看看"
            输出: {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"},"candidates":["MONITOR"]}
            
            输入: "帮我改下 redis 的 maxmemory 配置"
            输出: {"intent":"CONFIGURE","confidence":0.9,"entities":{"service":"redis","config":"maxmemory"},"candidates":[]}
            
            输入: "看下服务器磁盘使用情况"
            输出: {"intent":"MONITOR","confidence":0.9,"entities":{"resource":"disk"},"candidates":[]}
            
            输入: "这个命令 awk '{print $1}' access.log 是什么意思"
            输出: {"intent":"EXPLAIN","confidence":0.95,"entities":{"command":"awk"},"candidates":[]}
            
            输入: "看下 nginx 502 是不是因为我刚改了 redis 配置导致连接池打满"
            输出: {"intent":"COMPOUND","confidence":0.85,"entities":{"service":"nginx","error":"502"},"candidates":["DIAGNOSE","MONITOR","CONFIGURE"]}
            
            ## 对话上下文（最近意图历史）
            {{CONTEXT}}

            ## 待分类的用户输入
            "{{MESSAGE}}"

            输出（仅返回 JSON）:
            """;


    /**
     * 对用户消息执行意图分类。
     *
     * <p>该公共入口保留原有方法签名，避免现有调用方和测试受到影响。</p>
     *
     * @param message 用户原始消息
     * @param context 当前会话上下文，可为 null
     * @return System One 或原有 LLM 产生的意图结果
     */
    public IntentResultVO classify(String message, ConversationContextVO context) {
        return classify(message, context, Map.of());
    }

    /**
     * 执行完整的第二层意图分类。
     *
     * <p>先尝试 System One；如果不能使用其结果，再调用原有 LLM。</p>
     *
     * @param message          用户原始消息
     * @param context          当前会话上下文，可为 null
     * @param fallbackEntities 规则分类器已经提取出的实体
     * @return 最终意图分类结果
     */
    private IntentResultVO classify(String message, ConversationContextVO context, Map<String, String> fallbackEntities) {
        String contextDescription = getContextDescription(context);

        /*
         * Jev/Laya 是快速分类优先级。
         * Optional.empty() 会自然进入原有 LLM 降级路径。
         */
        Optional<IntentResultVO> systemOneResult = classifyWithSystemOne(message, contextDescription, fallbackEntities);

        if (systemOneResult.isPresent()) {
            return systemOneResult.get();
        }

        // System One 不可用时渲染原有 LLM 提示词。
        String prompt = getPrompt(message, contextDescription);

        /*
         * ChatModel 尚未配置时不能执行原有 LLM 分类。
         * 返回 UNKNOWN 后由主 Agent 自行理解输入。
         */
        if (chatModel == null) {
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0)
                    .entities(Map.of())
                    .build();
        }

        try {
            String response = chatModel.call(prompt);
            return parseResponse(response);
        } catch (Exception exception) {
            /*
             * 分类器失败不能中断用户主请求。
             * 不在 INFO 日志打印 Prompt 或用户消息。
             */
            log.warn("原有 LLM 意图分类调用失败 model={} exception={} message={}",
                    modelName,
                    exception.getClass().getSimpleName(),
                    exception.getMessage());

            log.debug("原有 LLM 意图分类异常详情", exception);

            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0)
                    .entities(Map.of())
                    .build();
        }
    }


    /**
     * 通过领域端口尝试执行 Jev/Laya 快速意图分类。
     *
     * @param message            用户原始消息
     * @param contextDescription 已渲染的对话上下文描述
     * @param fallbackEntities   规则层提取出的实体
     * @return 可以正式使用时返回 IntentResultVO，否则返回 Optional.empty()
     */
    private Optional<IntentResultVO> classifyWithSystemOne(String message, String contextDescription, Map<String, String> fallbackEntities) {
        /*
         * 允许现有单元测试继续直接 new LLMIntentClassifierNode()。
         * 非 Spring 环境中字段没有注入时直接走原有 LLM 路径。
         */
        if (intentDecisionPort == null) {
            return Optional.empty();
        }

        try {
            IIntentDecisionPort.IntentDecisionRequest request =
                    new IIntentDecisionPort.IntentDecisionRequest(
                            message,
                            contextDescription,
                            SYSTEM_ONE_INTENT_CRITERIA);

            return intentDecisionPort
                    .classify(request)
                    .map(decision ->
                            toIntentResult(decision, fallbackEntities));
        } catch (RuntimeException exception) {
            /*
             * 即使第三方 Port 实现抛出未处理异常，
             * 当前节点仍然保证回退到原有 LLM。
             */
            log.warn("System One 意图分类端口异常，回退原有 LLM exception={} message={}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage());

            log.debug("System One 意图分类端口异常详情", exception);

            return Optional.empty();
        }
    }

    /**
     * 把供应商无关的 IntentDecision 转换成系统现有 IntentResultVO。
     *
     * @param decision         Jev/Laya 结构化意图决策结果
     * @param fallbackEntities 规则层已经提取的实体
     * @return 可供现有意图链路直接使用的结果
     */
    private IntentResultVO toIntentResult(IIntentDecisionPort.IntentDecision decision, Map<String, String> fallbackEntities) {
        /*
         * 从完整概率分布中选出三个次高意图。
         * 排除主意图和 UNKNOWN，避免候选项重复或没有业务价值。
         */
        List<IntentTypeEnumVO> candidates =
                decision.probabilities()
                        .entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getKey()
                                        != decision.intent())
                        .filter(entry ->
                                entry.getKey()
                                        != IntentTypeEnumVO.UNKNOWN)
                        .sorted(
                                Map.Entry
                                        .<IntentTypeEnumVO, Double>
                                                comparingByValue()
                                        .reversed())
                        .limit(3)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

        /*
         * Jev/Laya 当前只负责固定候选意图选择，
         * 不负责自由格式实体抽取。
         *
         * 因此保留规则层已经识别出的 service、error 等实体。
         */
        Map<String, String> entities =
                fallbackEntities == null
                        || fallbackEntities.isEmpty()
                        ? Map.of()
                        : Map.copyOf(fallbackEntities);

        return IntentResultVO.builder()
                .intent(decision.intent())
                /*
                 * 现有下游根据 confidence 做路由。
                 * 这里写入跨供应商统一的答案概率。
                 */
                .confidence(decision.answerProbability())
                .entities(entities)
                .candidateIntents(candidates)
                .rawResponse(
                        decision.rawResponse())
                .build();
    }


    /**
     * 把会话上下文转换为分类器可读的简短文本。
     *
     * @param context 当前会话上下文，可为 null
     * @return 最近意图和进行中任务的文本描述；没有上下文时返回“无”
     */
    private static String getContextDescription(ConversationContextVO context) {
        String recentIntents = "";
        if (context != null && context.getRecentIntents() != null) {
            recentIntents = context
                    .getRecentIntents()
                    .stream()
                    .map(history ->
                            history.getIntent().name())
                    .collect(
                            Collectors.joining(", "));
        }

        String taskDescription = getTaskDescription(context);

        String contextDescription =
                (recentIntents.isEmpty() ? "" : "最近意图: " + recentIntents) +
                        (taskDescription.equals("无") ? "" : (recentIntents.isEmpty() ? "" : " | ") + taskDescription);

        return contextDescription.isEmpty() ? "无" : contextDescription;
    }

    private static @NonNull String getTaskDescription(ConversationContextVO context) {
        String taskDescription = "无";

        /*
         * 只把尚未完成的任务放入上下文。
         * 已完成任务不应影响新消息的意图判断。
         */
        if (context != null
                && context.getTaskState() != null
                && !context.getTaskState().isCompleted()
                && context.getTaskState()
                .getRootIntent() != null) {
            taskDescription =
                    "进行中任务: "
                            + context.getTaskState()
                            .getRootIntent()
                            + ", 当前步骤: "
                            + context.getTaskState()
                            .getCurrentStepIndex();
        }
        return taskDescription;
    }

    /**
     * 渲染原有 LLM 分类提示词。
     *
     * @param message            用户原始消息
     * @param contextDescription 对话上下文描述
     * @return 完整分类提示词
     */
    private static @NotNull String getPrompt(String message, String contextDescription) {
        return CLASSIFY_PROMPT_TEMPLATE
                .replace("{{CONTEXT}}", contextDescription)
                .replace("{{MESSAGE}}", message);
    }

    /**
     * 解析原有 LLM 返回的 JSON。
     *
     * <p>模型可能在 JSON 前后增加解释性文本，因此先提取第一个 JSON 对象，
     * 再解析 intent、confidence、entities 和 candidates。</p>
     *
     * @param response 原有 LLM 返回的文本
     * @return 解析成功时返回意图结果，失败时返回 UNKNOWN
     */
    private IntentResultVO parseResponse(String response) {
        try {
            /*
             * 提取响应中第一个完整 JSON 对象。
             * 兼容模型在 JSON 前后输出少量说明文字的情况。
             */
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");

            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            IntentTypeEnumVO intent =
                    IntentTypeEnumVO.valueOf(
                            String.valueOf(parsed.get("intent")).toUpperCase());

            double confidence =
                    parsed.containsKey("confidence") ?
                            Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;

            Map<String, String> entities = normalizeEntities(parsed.get("entities"));

            List<IntentTypeEnumVO> candidates = List.of();

            if (parsed.containsKey("candidates")) {
                Object rawCandidates = parsed.get("candidates");
                if (rawCandidates instanceof List<?> list) {
                    candidates = list.stream()
                            .filter(item -> item instanceof String)
                            .map(item -> {
                                try {
                                    return IntentTypeEnumVO.valueOf(((String) item).toUpperCase());
                                } catch (IllegalArgumentException exception) {
                                    return null;
                                }
                            })
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());
                }
            }

            return IntentResultVO.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .entities(entities)
                    .rawResponse(response)
                    .candidateIntents(candidates)
                    .build();
        } catch (Exception exception) {
            /*
             * 意图分类失败属于可降级错误。
             * 返回 UNKNOWN 后由主 Agent 自行理解消息。
             */
            log.debug("解析原有 LLM 意图分类响应失败", exception);

            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0)
                    .entities(Map.of())
                    .rawResponse(response)
                    .build();
        }
    }

    /**
     * Jackson 将模型 JSON 解析为 Map<String, Object>；泛型强转不会检查 Map 中的值。
     * 模型可能把多条命令放进数组，因此在边界处统一转成字符串，避免下游构建 Prompt 时
     * 将 ArrayList 当作 String 读取而抛 ClassCastException。
     */
    private Map<String, String> normalizeEntities(Object rawEntities) {
        if (rawEntities == null) {
            return Map.of();
        }
        if (!(rawEntities instanceof Map<?, ?> rawMap)) {
            log.warn("意图识别 entities 不是对象，已忽略 | type:{}", rawEntities.getClass().getSimpleName());
            return Map.of();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank() || value == null) {
                return;
            }
            String text = value instanceof Map<?, ?> || value instanceof List<?>
                    ? objectMapper.valueToTree(value).toString()
                    : String.valueOf(value);
            normalized.put(name, text);
        });
        return normalized;
    }

    /**
     * 业务流程处理方法
     * <p>
     * 子类需要实现此方法来定义具体的业务处理逻辑。
     * 该方法在异步数据加载完成后执行。
     * </p>
     *
     * @param requestParameter 请求参数
     * @param dynamicContext   动态上下文
     * @return 处理结果
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    protected IntentResultVO doApply(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws Exception {

        ConversationContextVO context = contextTracker.getContext(requestParameter.getChatSessionId());
        // 配置大模型
        configure(requestParameter.getLlmIntentOpenAiApi(), requestParameter.getLlmIntentModelName());

        IntentResultVO intentResultVO = classify(requestParameter.getUserMessage(), context);
        if (dynamicContext.isFailuresStatus() && intentResultVO.getConfidence() < 0.3) {
            log.info("用户识别错误次数过多，且Llm 用户识别意图结果的置信度:{} < 0.3, 交由主模型处理 返回识别类型：{}",
                    intentResultVO.getConfidence(), intentResultVO.getIntent());
            dynamicContext.setFinalResult(toUnknown(intentResultVO));
            return router(requestParameter, dynamicContext);
        }

        if (dynamicContext.isFailuresStatus() && intentResultVO.getConfidence() >= 0.3) {
            log.info("用户识别错误次数过多，且Llm 用户识别意图结果的置信度:{} > 0.3, 直接返回结果：识别类型：{}",
                    intentResultVO.getConfidence(), intentResultVO.getIntent());
            dynamicContext.setFinalResult(intentResultVO);
            return router(requestParameter, dynamicContext);
        }

        if (intentResultVO.getConfidence() > 0.5) {
            log.info("Llm 用户识别意图结果：意图类型：{} | 置信度:{} | ", intentResultVO.getIntent(), intentResultVO.getConfidence());
            dynamicContext.setFinalResult(intentResultVO);
        }
        return router(requestParameter, dynamicContext);
    }

    /**
     * 构建 Jev/Laya 使用的意图候选项。
     *
     * <p>候选描述应保持简短。Laya 的 Choice 选项共享模型 head token budget，
     * 描述过长会增加本地推理负担。</p>
     *
     * @return 按定义顺序排列且不可修改的意图候选项
     */
    private static Map<IntentTypeEnumVO, String> buildSystemOneIntentCriteria() {
        Map<IntentTypeEnumVO, String> criteria = new LinkedHashMap<>();

        criteria.put(IntentTypeEnumVO.DIAGNOSE, "排障并定位故障原因");
        criteria.put(IntentTypeEnumVO.CONFIGURE, "修改配置文件或运行参数");
        criteria.put(IntentTypeEnumVO.DEPLOY, "部署、发布、升级或回滚");
        criteria.put(IntentTypeEnumVO.MONITOR, "查看日志、状态或资源指标");
        criteria.put(IntentTypeEnumVO.SECURITY, "权限、防火墙、证书或漏洞");
        criteria.put(IntentTypeEnumVO.BACKUP, "备份、恢复、导入或迁移");
        criteria.put(IntentTypeEnumVO.EXECUTE, "直接执行明确命令或操作");
        criteria.put(IntentTypeEnumVO.COMPOUND, "同时包含两个及以上明确任务");
        criteria.put(IntentTypeEnumVO.EXPLAIN, "解释命令、配置或技术概念");
        criteria.put(IntentTypeEnumVO.SEARCH, "查找文件、进程、端口或内容");
        criteria.put(IntentTypeEnumVO.CHAT, "闲聊或非运维交流");
        criteria.put(IntentTypeEnumVO.CONTINUE, "继续当前尚未完成的任务");
        criteria.put(IntentTypeEnumVO.UNKNOWN, "信息不足或不属于其他类型");

        return Collections.unmodifiableMap(criteria);
    }

    /**
     * 获取待执行的策略处理器
     * <p>
     * 根据请求参数和动态上下文的内容，选择并返回合适的策略处理器。
     * 实现类需要根据具体的业务规则来实现策略选择逻辑。
     * </p>
     *
     * @param requestParameter 请求参数，用于确定策略选择的依据
     * @param dynamicContext   动态上下文，包含策略选择过程中需要的额外信息
     * @return 选择的策略处理器，如果没有找到合适的策略则返回null
     * @throws Exception 策略选择过程中可能抛出的异常
     */
    @Override
    public StrategyHandler<IntentRequestVO, DefaultClassifyFactory.DynamicContext, IntentResultVO> get(IntentRequestVO requestParameter, DefaultClassifyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("endIntentClassifierNode");
    }
}
