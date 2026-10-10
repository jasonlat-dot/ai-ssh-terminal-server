package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh;

import com.google.adk.events.Event;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.AgentInvocationSupport;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.register.AdkToolProvider;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.CommandSafetyPolicy;
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

    /** 统一的命令安全责任链入口，由 Spring 明确注入链组件。 */
    @Resource(name = "commandSafetyPolicyChain")
    private CommandSafetyPolicy commandSafetyPolicy;

    public SshExecuteAdkTool() {
        super("executeCommand", "在当前请求绑定的 SSH 终端中执行 Shell 命令");
    }

    @Override
    public List<? extends BaseTool> getAdkTool() {
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
            AgentInvocationContext invocation = AgentInvocationSupport.require(toolContext);
            AgentRunCancellation cancellation = invocation.cancellation();
            try {
                if (cancellation != null) {
                    cancellation.registerCurrentThread();
                }
                String command = String.valueOf(args.getOrDefault("command", ""));
                String terminalSessionId = invocation.terminalSessionId();

                String agentName = invocation.runnerAgentName();
                if (agentName == null || agentName.isBlank()) {
                    agentName = toolContext.agentName();
                }
                // rootSessionId 用于事件路由；其余两个 ID 只用于恢复 UI 中的父子层级。
                String rootSessionId = invocation.rootSessionId();
                String agentCallId = invocation.agentCallId();
                String parentToolCallId = invocation.parentToolCallId();
                String callId = toolContext.functionCallId().orElseGet(() -> "ssh_" + Event.generateEventId());

                // 在真正执行前主动发布 FunctionCall，前端无需等命令结束即可显示“调用中”。
                publishToolEvent(rootSessionId, toolContext.invocationId(), callId,
                        args, agentName, agentCallId, parentToolCallId, false);

                log.info("SSH 工具调用开始 agentName:{} invocationId={}, toolCallId={}, terminalSessionId={}, command={}",
                        agentName, toolContext.invocationId(), toolContext.functionCallId().orElse(""),
                        terminalSessionId, command);
                Map<String, Object> executeResult = executeForTerminal(
                        terminalSessionId,
                        command,
                        invocation,
                        agentName);
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
    private Map<String, Object> executeForTerminal(
            String terminalSessionId,
            String command,
            AgentInvocationContext invocation,
            String agentName
    ) throws InterruptedException {
        String safeCommand = command == null ? "" : command;

        /*
         * 责任链统一执行本地硬规则、终端检查与 Jev/Laya 语义判断。
         * 工具只处理最终结果；只有所有节点允许后才进入真实 SSH 执行通道。
         */
        CommandSafetyDecision decision = commandSafetyPolicy.evaluate(
                CommandSafetyContext.of(safeCommand, terminalSessionId, invocation, agentName));
        if (!decision.isAllowed()) {
            return rejectedBySafetyPolicy(terminalSessionId, safeCommand, agentName, decision);
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
     * 将责任链的拒绝结果转换成工具响应，供模型与前端统一展示。
     *
     * <p>终端前置条件失败仍按普通执行失败返回；安全策略拒绝附带 blocked、
     * ruleId 和 riskLevel。外部语义拒绝额外携带概率与供应商信息，原始响应
     * 不写入普通日志，也不返回给模型。</p>
     *
     * @param terminalSessionId 当前终端 ID，仅用于审计关联
     * @param command 原始执行命令，保持与模型请求一致
     * @param agentName 实际执行工具的 Agent 名称
     * @param decision 责任链返回的首个拒绝结果
     * @return 与原工具协议兼容的失败或拦截响应
     */
    private Map<String, Object> rejectedBySafetyPolicy(
            String terminalSessionId,
            String command,
            String agentName,
            CommandSafetyDecision decision
    ) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("command", command);

        // 未绑定或已关闭终端属于执行前置条件失败，不标记为危险命令。
        if (!decision.isSafetyViolation()) {
            log.warn("SSH 命令执行前置条件失败 command={} agentName={} ruleId={} terminalSessionId={}",
                    truncateForLog(command), agentName, decision.getRuleId(), terminalSessionId);
            result.put("output", decision.getReason());
            return result;
        }

        result.put("blocked", true);
        result.put("ruleId", decision.getRuleId());
        var semanticDecision = decision.getSemanticDecision();
        if (semanticDecision != null) {
            // 审计证据由策略节点提供；工具只投影已有结果，不再次发起语义判断。
            log.warn("SSH 命令被 System One 拦截 command={} agentName={} risk={} "
                            + "answerProbability={} provider={} model={} terminalSessionId={}",
                    truncateForLog(command), agentName, semanticDecision.choice(),
                    semanticDecision.answerProbability(), semanticDecision.provider(),
                    semanticDecision.model(), terminalSessionId);
            result.put("riskLevel", semanticDecision.choice().name());
            result.put("answerProbability", semanticDecision.answerProbability());
            result.put("provider", semanticDecision.provider());
            result.put("model", semanticDecision.model());
            result.put("output", "⚠️ 命令已被智能安全策略拦截：" + decision.getReason()
                    + "\n如确认必须执行，请登录终端后人工操作。");
        } else {
            log.warn("SSH 命令被安全策略拦截 command={} agentName={} ruleId={} reason={} terminalSessionId={}",
                    truncateForLog(command), agentName, decision.getRuleId(),
                    decision.getReason(), terminalSessionId);
            result.put("riskLevel", "DENIED");
            result.put("output", "⚠️ 命令已被安全策略拦截：" + decision.getReason()
                    + "\n如确认必须执行，请登录终端后人工操作。");
        }
        return result;
    }

    /** 普通日志中的命令最多保留 512 个字符，避免超长载荷撑大日志。 */
    private String truncateForLog(String command) {
        if (command == null) {
            return "";
        }
        return command.length() <= 512 ? command : command.substring(0, 512);
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
