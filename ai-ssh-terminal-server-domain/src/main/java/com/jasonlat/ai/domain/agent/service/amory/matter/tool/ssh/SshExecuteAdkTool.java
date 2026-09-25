package com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh;

import com.google.adk.events.Event;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyPolicy;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSH 命令 ADK 工具。
 *
 * <p>本类直接实现 BaseTool，ADK 的 ToolContext 只停留在适配入口 {@link #runAsync}。
 * 真实业务执行方法 {@link #executeForTerminal} 不依赖 ADK，也不会把终端会话 ID 暴露给模型。</p>
 */
@Slf4j
@Service("sshExecuteAdkTool")
public class SshExecuteAdkTool extends BaseTool implements AdkToolProvider {

    /**
     * 可选事件发布器；为空或工具在测试中直接构造时，SSH 命令仍可正常执行，只是不回流可视化事件。
     */
    @Resource
    private AgentEventPublisher agentEventPublisher;

    private static final FunctionDeclaration DECLARATION = FunctionDeclaration.builder()
            .name("executeCommand")
            .description("在当前请求绑定的 SSH 终端中执行 Shell 命令")
            .parameters(Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of(
                            "command",
                            Schema.builder()
                                    .type(Type.Known.STRING)
                                    .description("要执行的 Shell 命令，如: ls -la, docker --version")
                                    .build()))
                    .required(List.of("command"))
                    .build())
            .build();

    @Resource
    private ISshTerminalService sshTerminalService;
    @Resource
    private CommandSafetyPolicy commandSafetyPolicy;

    public SshExecuteAdkTool() {
        super("executeCommand", "在当前请求绑定的 SSH 终端中执行 Shell 命令");
    }

    @Override
    public List<? extends BaseTool> getTools() {
        return List.of(this);
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(DECLARATION);
    }

    /**
     * ADK 适配入口。这里只负责解析模型参数和请求级 Session state。
     * <p>
     * 子 Agent 场景下，父业务 sessionId、agentCallId 和 parentToolCallId 都由派发服务写入
     * 子 Session state；本方法读取后附加到工具事件，使前端能把调用归到正确的子 Agent。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            Object cancellationValue = toolContext.state().get(RUN_CANCELLATION);
            AgentRunCancellation cancellation = cancellationValue instanceof AgentRunCancellation value ? value : null;
            try {
                if (cancellation != null) cancellation.registerCurrentThread();
                String command = String.valueOf(args.getOrDefault("command", ""));
                Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
                String terminalSessionId = terminalValue instanceof String value ? value : null;

                String agentName = (String) toolContext.state().get(AdkToolProvider.RUNNER_AGENT_NAME);
                if (agentName == null || agentName.isBlank()) {
                    agentName = toolContext.agentName();
                }
                // rootSessionId 用于事件路由；其余两个 ID 只用于恢复 UI 中的父子层级。
                String rootSessionId = (String) toolContext.state().get(AdkToolProvider.PARENT_SESSION_ID);
                String agentCallId = (String) toolContext.state().get(AdkToolProvider.NESTED_AGENT_CALL_ID);
                String parentToolCallId = (String) toolContext.state().get(AdkToolProvider.PARENT_TOOL_CALL_ID);
                String callId = toolContext.functionCallId().orElseGet(() -> "ssh_" + Event.generateEventId());

                // 在真正执行前主动发布 FunctionCall，前端无需等命令结束即可显示“调用中”。
                publishToolEvent(rootSessionId, toolContext.invocationId(), callId,
                        args, agentName, agentCallId, parentToolCallId, false);

                log.info("SSH 工具调用开始 agentName:{} invocationId={}, toolCallId={}, terminalSessionId={}, command={}",
                        agentName, toolContext.invocationId(), toolContext.functionCallId().orElse(""),
                        terminalSessionId, command);
                Map<String, Object> executeResult = executeForTerminal(terminalSessionId, command);
                if (cancellation != null) cancellation.throwIfCancelled();

                // 使用相同 callId 发布 FunctionResponse，前端据此把 running 更新为 success/error。
                publishToolEvent(rootSessionId, toolContext.invocationId(), callId,
                        executeResult, agentName, agentCallId, parentToolCallId, true);
                return executeResult;
            } finally {
                if (cancellation != null) cancellation.unregisterCurrentThread();
            }
        });
    }

    /**
     * 真实业务执行入口。终端 ID 由 ADK 适配层提供，方法本身不感知 ToolContext。
     */
    private Map<String, Object> executeForTerminal(String terminalSessionId, String command) throws InterruptedException {
        String safeCommand = command == null ? "" : command;
        CommandSafetyDecision decision = commandSafetyPolicy.evaluate(safeCommand);
        if (!decision.isAllowed()) {
            log.warn("SSH 命令被安全策略拦截 terminalSessionId={}, ruleId={}, reason={}, command={}",
                    terminalSessionId, decision.getRuleId(), decision.getReason(), safeCommand);
            return Map.of(
                    "success", false,
                    "blocked", true,
                    "ruleId", decision.getRuleId(),
                    "riskLevel", "DENIED",
                    "output", "⚠️ 命令已被安全策略拦截：" + decision.getReason()
                            + "\n如确认必须执行，请登录终端后人工操作。",
                    "command", safeCommand);
        }

        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            log.warn("SSH 工具缺少请求级终端会话 ID，command={}", safeCommand);
            return Map.of(
                    "success", false,
                    "output", "未绑定 SSH 终端会话。请先打开 SSH 终端连接。",
                    "command", safeCommand);
        }
        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            log.warn("SSH 终端会话不存在 terminalSessionId={}", terminalSessionId);
            return Map.of(
                    "success", false,
                    "output", "SSH 终端会话不存在或已关闭: " + terminalSessionId,
                    "command", safeCommand);
        }

        try {
            String output = sshTerminalService.executeCommand(terminalSessionId, safeCommand);
            boolean success = isExecutionSuccessful(output);
            Map<String, Object> result = new HashMap<>();
            result.put("command", safeCommand);
            result.put("output", output);
            result.put("success", success);
            if (!success) {
                result.put("suggestion", analyzeError(output));
            }
            log.info("SSH 工具调用完成 terminalSessionId={}, success={}, outputLength={}",
                    terminalSessionId, success, output == null ? 0 : output.length());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.info("SSH 命令因对话停止而中断 terminalSessionId={}, command={}", terminalSessionId, safeCommand);
            throw interrupted;
        } catch (Exception exception) {
            log.error("SSH 命令执行异常 terminalSessionId={}, command={}",
                    terminalSessionId, safeCommand, exception);
            return Map.of(
                    "success", false,
                    "output", "命令执行异常: " + exception.getMessage(),
                    "command", safeCommand);
        }
    }

    /**
     * 发布 SSH 工具的合成调用/响应事件。
     * <p>
     * SSH 工具的执行结果不一定会作为父 Runner 的原始 functionResponse 返回，
     * 因此这里构造标准 ADK Event，并按父业务会话投递给当前流式监听器。
     * Runner 后续也可能产生同一调用的原始事件，Case 层会使用 callId 去重。
     *
     * @param rootSessionId   父对话 sessionId，决定事件进入哪条 /chat_stream
     * @param toolInvocationId 当前工具所属的 ADK invocationId，仅写入事件便于追踪
     * @param callId          一次真实工具调用的 ID，调用和结果必须保持一致
     * @param payload         调用阶段为工具参数，结果阶段为 success/output/command 等结果
     * @param author          实际执行工具的 Agent 名称
     * @param agentCallId     子 Agent 执行 ID；为空表示不归属于子 Agent
     * @param parentToolCallId 触发子 Agent 派发的父工具调用 ID
     * @param result          false 构造 FunctionCall，true 构造 FunctionResponse
     */
    private void publishToolEvent(String rootSessionId, String toolInvocationId, String callId,
                                  Map<String, Object> payload, String author,
                                  String agentCallId, String parentToolCallId, boolean result) {
        if (agentEventPublisher == null || rootSessionId == null || rootSessionId.isBlank()) {
            return;
        }
        Part part = result
                ? Part.builder().functionResponse(FunctionResponse.builder()
                        .id(callId).name("executeCommand").response(payload).build()).build()
                : Part.builder().functionCall(FunctionCall.builder()
                        .id(callId).name("executeCommand").args(payload).build()).build();
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(toolInvocationId)
                .author(author != null && !author.isBlank() ? author : "executeCommand")
                .content(Content.fromParts(part))
                .build();
        agentEventPublisher.publishToSession(rootSessionId, event,
                agentCallId, parentToolCallId, author);
    }

    private boolean isExecutionSuccessful(String output) {
        /*
         * TerminalSessionPortSupport 仅在 Shell 返回非零退出码时，才会在结果末尾追加
         * “[命令退出码: N]”。命令输出本身可能是日志或诊断信息，其中出现 error、failed
         * 等文本并不代表本次命令执行失败，因此这里只依据真实退出码标记判断。
         */
        return output == null || !output.contains("[命令退出码:");
    }

    private String analyzeError(String output) {
        if (output == null) return null;
        String lowerOutput = output.toLowerCase();
        if (lowerOutput.contains("command not found")) {
            return "命令不存在。请检查命令名称、软件是否安装以及 PATH。";
        }
        if (lowerOutput.contains("permission denied")) {
            return "权限不足。请检查用户权限，必要时人工确认后使用 sudo。";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "文件或目录不存在。请检查路径是否正确。";
        }
        if (lowerOutput.contains("connection refused") || lowerOutput.contains("network is unreachable")) {
            return "网络连接失败。请检查目标服务、网络与防火墙。";
        }
        return "执行失败，请检查命令和输出信息。";
    }
}
