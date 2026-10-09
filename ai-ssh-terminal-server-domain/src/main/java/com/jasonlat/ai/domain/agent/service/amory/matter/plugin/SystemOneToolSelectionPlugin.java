package com.jasonlat.ai.domain.agent.service.amory.matter.plugin;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.adapter.port.IToolSelectionDecisionPort;
import com.jasonlat.ai.domain.agent.adapter.port.IToolSelectionDecisionPort.ToolCandidate;
import com.jasonlat.ai.domain.agent.adapter.port.IToolSelectionDecisionPort.ToolSelectionDecision;
import com.jasonlat.ai.domain.agent.adapter.port.IToolSelectionDecisionPort.ToolSelectionDecisionRequest;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 在 ADK 每次向模型发送请求前执行 System One 工具筛选。
 *
 * <p>Agent 创建时仍然挂载完整工具集合。本插件只修改当前这一次
 * {@link LlmRequest.Builder}，不会永久删除 Agent 工具，因此下一轮请求仍然从完整集合
 * 开始判断。插件通过 {@link IToolSelectionDecisionPort} 与 Jev/Laya 解耦。</p>
 *
 * <p>所有异常都采用 fail-open：只要端口关闭、处于影子模式、调用失败、结果非法，
 * 或筛选后无法得到安全集合，就保持原始工具 Map 不变。</p>
 */
@Slf4j
@Component
public class SystemOneToolSelectionPlugin extends BasePlugin implements Ordered {

    /**
     * 工具筛选必须早于日志类插件执行，确保日志看到的是最终发送给模型的工具集合。
     *
     * <p>插件顺序遵循 Spring {@link Ordered} 约定：数值越小，执行优先级越高。</p>
     */
    private static final int PLUGIN_ORDER = 100;

    /** 最近上下文最多采集的 Content 数量，避免遍历并复制整段历史。 */
    private static final int MAX_RECENT_CONTENTS = 12;

    /** 单段自然语言最多保留的字符数；适配器还会执行总长度截断。 */
    private static final int MAX_TEXT_PART_CHARACTERS = 800;

    /** System One 工具筛选领域端口。 */
    private final IToolSelectionDecisionPort toolSelectionDecisionPort;

    /**
     * 创建自动工具筛选插件。
     *
     * @param toolSelectionDecisionPort Jev/Laya 工具筛选端口
     */
    public SystemOneToolSelectionPlugin(
            IToolSelectionDecisionPort toolSelectionDecisionPort
    ) {
        super("SystemOneToolSelectionPlugin");
        this.toolSelectionDecisionPort = toolSelectionDecisionPort;
    }

    /**
     * 返回当前插件在 Runner 插件链中的执行顺序。
     *
     * @return {@code 100}，保证工具筛选先于普通日志和观测插件执行
     */
    @Override
    public int getOrder() {
        return PLUGIN_ORDER;
    }

    /**
     * 在模型请求发出前筛选本轮可见工具。
     *
     * @param callbackContext 当前 ADK 调用上下文，可取得主/子 Agent 名称和用户任务
     * @param llmRequest      即将发送的模型请求 Builder，允许替换本轮工具 Map
     * @return 插件不直接生成模型响应，因此始终返回只执行副作用的 Maybe
     */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        return Maybe.fromAction(() -> filterTools(callbackContext, llmRequest));
    }

    /**
     * 执行一次 fail-open 工具筛选。
     *
     * @param callbackContext 当前 ADK 回调上下文
     * @param requestBuilder  即将交给模型的请求 Builder
     */
    private void filterTools(CallbackContext callbackContext, LlmRequest.Builder requestBuilder) {
        LlmRequest request = requestBuilder.build();
        Map<String, BaseTool> originalTools = request.tools();

        // 零个或一个工具没有筛选价值，也不能满足领域端口“至少两个候选”的约束。
        if (originalTools == null || originalTools.size() < 2) {
            return;
        }

        try {
            String userMessage = extractUserMessage(callbackContext.userContent());
            String recentContext = buildRecentContext(request.contents());

            /*
             * 纯媒体请求可能没有文本。如果最近上下文也无法提供安全摘要，直接保留全部工具，
             * 避免在缺少任务语义时进行猜测性过滤。
             */
            if (userMessage.isBlank() && recentContext.isBlank()) {
                return;
            }

            /*
             * 使用 LlmRequest 工具 Map 的 key 作为唯一名称。这样即使某个第三方 BaseTool
             * 的 name() 与注册 key 不一致，筛选结果仍能准确映射回待发送的 Map。
             */
            List<ToolCandidate> candidates = originalTools.entrySet().stream()
                    .map(entry -> new ToolCandidate(
                            entry.getKey(),
                            entry.getValue().description()))
                    .toList();

            ToolSelectionDecisionRequest selectionRequest =
                    new ToolSelectionDecisionRequest(
                            userMessage,
                            recentContext,
                            callbackContext.agentName(),
                            resolveCurrentIntent(callbackContext),
                            candidates);

            Optional<ToolSelectionDecision> optionalDecision = toolSelectionDecisionPort.select(selectionRequest);

            // empty 包含关闭、影子模式、低概率、超时和非法响应等所有回退场景。
            if (optionalDecision.isEmpty()) {
                return;
            }

            ToolSelectionDecision decision = optionalDecision.get();
            Map<String, BaseTool> filteredTools = retainInOriginalOrder(
                    originalTools,
                    decision.selectedToolNames());

            /*
             * 领域结果正常情况下至少保留一个工具；这里再次防御第三方 Port 实现，
             * 绝不能把空工具集写回 LlmRequest。
             */
            if (filteredTools.isEmpty()) {
                log.warn("忽略空工具筛选结果 agentName={} availableTools={}",
                        callbackContext.agentName(),
                        originalTools.keySet());
                return;
            }

            requestBuilder.tools(filteredTools);

            Set<String> removedTools = new LinkedHashSet<>(originalTools.keySet());
            removedTools.removeAll(filteredTools.keySet());

            log.info(
                    "System One 工具筛选已应用 agentName={} retainedTools={} removedTools={} "
                            + "availableCount={} retainedCount={} provider={} model={}",
                    callbackContext.agentName(),
                    filteredTools.keySet(),
                    removedTools,
                    originalTools.size(),
                    filteredTools.size(),
                    decision.provider(),
                    decision.model());
        } catch (RuntimeException exception) {
            /*
             * 工具筛选只是优化层。端口实现、上下文转换或第三方响应出现任何异常时，
             * 不修改 requestBuilder，后续模型仍然看到完整工具集合。
             */
            log.warn("System One 工具筛选异常，保留全部工具 agentName={} exception={} message={}",
                    callbackContext.agentName(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            log.debug("System One 工具筛选异常详情", exception);
        }
    }

    /**
     * 按原始工具 Map 顺序构建筛选结果。
     *
     * @param originalTools    ADK 原始工具集合
     * @param selectedToolNames 允许保留的工具名称
     * @return 顺序稳定的新 LinkedHashMap
     */
    private Map<String, BaseTool> retainInOriginalOrder(
            Map<String, BaseTool> originalTools,
            Set<String> selectedToolNames
    ) {
        Map<String, BaseTool> filtered = new LinkedHashMap<>();

        originalTools.forEach((name, tool) -> {
            if (selectedToolNames.contains(name)) {
                filtered.put(name, tool);
            }
        });

        return filtered;
    }

    /**
     * 提取当前调用最初收到的文本任务。
     *
     * <p>主 Agent 得到用户原始消息，子 Agent 得到父 Agent 下发的委派任务。</p>
     *
     * @param contentOptional ADK 用户内容
     * @return 合并后的文本；没有文本时返回空字符串
     */
    private String extractUserMessage(Optional<Content> contentOptional) {
        // 把 `content` 里面所有的文本片段拼接成一个完整字符串，片段之间换行分隔；空值、空白文本全部过滤，最后整体去首尾空白；如果对象为空直接返回空字符串
        return contentOptional.map(content -> content.parts().orElse(List.of()).stream()
                .map(part -> part.text().orElse(""))
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .trim()).orElse("");

    }

    /**
     * 构建不会泄露工具原始输出的最近上下文摘要。
     *
     * <p>只记录最近文本、调用过的工具名以及 FunctionResponse 的成功/失败状态；
     * 不发送命令结果正文、参数、附件或 Base64 内容。</p>
     *
     * @param contents 当前 LlmRequest 的完整 Content 列表
     * @return 可供工具相关性判断使用的安全摘要
     */
    private String buildRecentContext(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        // 取最后 MAX_RECENT_CONTENTS 条消息
        int startIndex = Math.max(0, contents.size() - MAX_RECENT_CONTENTS);
        List<String> summaries = new ArrayList<>();

        for (int index = startIndex; index < contents.size(); index++) {
            Content content = contents.get(index);
            String role = content.role().orElse("unknown");

            for (Part part : content.parts().orElse(List.of())) {
                // 1. 处理文本内容
                part.text()
                        .filter(text -> !text.isBlank())
                        .ifPresent(text -> summaries.add(
                                role + ": " + truncate(text, MAX_TEXT_PART_CHARACTERS)));
                // 2. 处理工具调用（functionCall）
                part.functionCall().ifPresent(call -> summaries.add(
                        "assistant called tool: " + call.name().orElse("unknown")));

                // 3. 处理工具返回结果（functionResponse）
                part.functionResponse().ifPresent(response -> summaries.add(
                        summarizeFunctionResponse(response)));
            }
        }

        return String.join("\n", summaries);
    }

    /**
     * 只提取工具响应名称和执行状态，不复制实际结果正文。
     *
     * @param response ADK FunctionResponse
     * @return 例如 {@code tool executeCommand completed: success}
     */
    private String summarizeFunctionResponse(FunctionResponse response) {
        Map<String, Object> values = response.response().orElse(Map.of());
        Object success = values.get("success");
        String status;

        if (Boolean.FALSE.equals(success) || values.containsKey("error")) {
            status = "failed";
        } else if (Boolean.TRUE.equals(success)) {
            status = "success";
        } else {
            status = "completed";
        }

        return "tool " + response.name().orElse("unknown") + " completed: " + status;
    }

    /**
     * 从本轮 Invocation state 中读取主 Agent 意图快照。
     *
     * @param callbackContext ADK 回调上下文
     * @return 意图枚举名称；缺失时返回空字符串
     */
    private String resolveCurrentIntent(CallbackContext callbackContext) {
        Object value = callbackContext.state().get(AgentInvocationContext.STATE_KEY);

        if (value instanceof AgentInvocationContext invocation && invocation.rootIntent() != null) {
            return invocation.rootIntent().name();
        }

        return "";
    }

    /**
     * 对单段文本执行安全截断。
     */
    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
