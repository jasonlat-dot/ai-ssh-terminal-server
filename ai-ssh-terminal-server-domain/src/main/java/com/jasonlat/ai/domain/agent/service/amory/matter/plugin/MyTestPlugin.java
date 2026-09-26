package com.jasonlat.ai.domain.agent.service.amory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    /** 按 invocation 汇总各次模型请求的 token，等 Agent 完整结束后只打印一次。 */
    private final ConcurrentMap<String, InvocationUsage> invocationUsages = new ConcurrentHashMap<>();

    public MyTestPlugin(String name) {
        super(name);
    }

    public MyTestPlugin() {
        super("MyTestPlugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🚀 用户输入信息 | invocationId:{} | userId:{} | partCount:{}",
                    invocationContext.invocationId(),
                    invocationContext.userId(),
                    userMessage.parts().map(java.util.List::size).orElse(0));
        });
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🤖 智能体启动 | agentName:{} | invocationId:{}",
                    agent.name(),
                    callbackContext.invocationId());
        });
    }

    @Override
    public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🤖 智能体完成 | agentName:{} | invocationId:{}",
                    agent.name(),
                    callbackContext.invocationId());
            InvocationUsage usage = invocationUsages.remove(callbackContext.invocationId());
            if (usage != null && usage.total.get() > 0) {
                log.info("插件日志-🧠 本次智能体 Token 总消耗 | invocationId:{} | input:{} | output:{} | total:{}",
                        callbackContext.invocationId(),
                        usage.input.get(), usage.output.get(), usage.total.get());
            }
        });
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        return Maybe.fromAction(() -> {
            LlmRequest request = llmRequest.build();
            String toolNames = request.tools().isEmpty()
                    ? "无"
                    : String.join(", ", request.tools().keySet());
            log.info("插件日志-🧠 大模型请求 | agent:{} | model:{} | 可用工具:[{}]",
                    callbackContext.agentName(),
                    request.model().orElse("default"),
                    toolNames);
            // 请求中可能包含附件正文和 Base64；只记录规模，避免打印正文或额外复制整份媒体。
            log.debug("上下文日志-📤 ADK 最终大模型请求 | invocationId:{} | agent:{} | messageCount:{}",
                    callbackContext.invocationId(),
                    callbackContext.agentName(),
                    request.contents().size());
        });
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        return Maybe.fromAction(() -> {
            String contentText = formatContent(llmResponse.content());
            log.debug("插件日志-🧠 大模型响应 | agent:{} | content:{} | turnComplete:{}",
                    callbackContext.agentName(),
                    contentText,
                    llmResponse.turnComplete().orElse(false));

            llmResponse.usageMetadata().ifPresent(usage -> recordUsage(
                    callbackContext,
                    usage.promptTokenCount().orElse(0),
                    usage.candidatesTokenCount().orElse(0),
                    usage.cachedContentTokenCount().orElse(0),
                    usage.totalTokenCount().orElse(0)));
        });

    }

    private void recordUsage(CallbackContext callbackContext, int input, int output, int cache, int total) {
        // 流式中间分片通常携带 0/0/0；真正的 usage 只在结束分片出现。
        if (input <= 0 && output <= 0 && total <= 0) {
            return;
        }
        log.info("插件日志-🧠 Token 消耗 | input:{} | output:{} | cache:{} | total:{}", input, output, cache, total);
        invocationUsages
                .computeIfAbsent(callbackContext.invocationId(), ignored -> new InvocationUsage())
                .addOnce(callbackContext.eventId(), input, output, total);
    }

    private static final class InvocationUsage {
        private final Set<String> recordedModelEvents = ConcurrentHashMap.newKeySet();
        private final AtomicInteger input = new AtomicInteger();
        private final AtomicInteger output = new AtomicInteger();
        private final AtomicInteger total = new AtomicInteger();

        private void addOnce(String eventId, int inputTokens, int outputTokens, int totalTokens) {
            if (!recordedModelEvents.add(eventId)) {
                return;
            }
            input.addAndGet(inputTokens);
            output.addAndGet(outputTokens);
            total.addAndGet(totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        }
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
        return Maybe.fromAction(() -> {
            if (tool instanceof AgentTool agentTool) {
                log.info("插件日志-🧩 子Agent派发开始 | parentAgent:{} | subAgent:{} | request:{} | invocationId:{}",
                        toolContext.agentName(),
                        agentTool.getAgent().name(),
                        formatArgs(toolArgs),
                        toolContext.invocationId());
                return;
            }
            log.info("插件日志-🔧 工具调用开始 | tool:{} | agent:{} | args:{}",
                    tool.name(),
                    toolContext.agentName(),
                    formatArgs(toolArgs));
        });
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Map<String, Object> result) {
        return Maybe.fromAction(() -> {
            if (tool instanceof AgentTool agentTool) {
                log.info("插件日志-🧩 子Agent派发完成 | parentAgent:{} | subAgent:{} | result:{} | invocationId:{}",
                        toolContext.agentName(),
                        agentTool.getAgent().name(),
                        formatArgs(result),
                        toolContext.invocationId());
                return;
            }
            log.info("插件日志-🔧 工具调用完成 | tool:{} | agent:{} | result:{}",
                    tool.name(),
                    toolContext.agentName(),
                    formatArgs(result));
        });
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
        return Maybe.fromAction(() -> {
            if (tool instanceof AgentTool agentTool) {
                log.error("插件日志-🧩 子Agent派发异常 | parentAgent:{} | subAgent:{} | request:{} | error:{}",
                        toolContext.agentName(),
                        agentTool.getAgent().name(),
                        formatArgs(toolArgs),
                        error.getMessage(), error);
                return;
            }
            log.error("插件日志-🔧 工具调用异常 | tool:{} | agent:{} | args:{} | error:{}",
                    tool.name(),
                    toolContext.agentName(),
                    formatArgs(toolArgs),
                    error.getMessage(), error);
        });
    }

    private String formatContent(Optional<Content> contentOptional) {
        if (contentOptional.isEmpty()) {
            return "None";
        }
        Content content = contentOptional.get();
        if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
            return "None";
        }
        String text = content.parts().get().stream()
                .map(part -> part.text().orElse(""))
                .collect(Collectors.joining("\n"))
                .trim();
        if (text.length() > 20) {
            return text.substring(0, 20) + "...";
        }
        return text;
    }

    private String formatArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        String str = args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        if (str.length() > 30) {
            return "{" + str.substring(0, 30) + "...}";
        }
        return "{" + str + "}";
    }

}
