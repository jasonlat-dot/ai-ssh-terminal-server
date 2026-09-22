package com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyPolicy;
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
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String command = String.valueOf(args.getOrDefault("command", ""));
            Object terminalValue = toolContext.state().get(TERMINAL_SESSION_STATE_KEY);
            String terminalSessionId = terminalValue instanceof String value ? value : null;
            log.info("SSH 工具调用开始 invocationId={}, toolCallId={}, terminalSessionId={}, command={}",
                    toolContext.invocationId(), toolContext.functionCallId().orElse(""),
                    terminalSessionId, command);
            return executeForTerminal(terminalSessionId, command);
        });
    }

    /**
     * 真实业务执行入口。终端 ID 由 ADK 适配层提供，方法本身不感知 ToolContext。
     */
    private Map<String, Object> executeForTerminal(String terminalSessionId, String command) {
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
        } catch (Exception exception) {
            log.error("SSH 命令执行异常 terminalSessionId={}, command={}",
                    terminalSessionId, safeCommand, exception);
            return Map.of(
                    "success", false,
                    "output", "命令执行异常: " + exception.getMessage(),
                    "command", safeCommand);
        }
    }

    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }
        String lowerOutput = output.toLowerCase();
        String[] errorIndicators = {
                "命令退出码:", "command not found", "no such file or directory", "permission denied",
                "operation not permitted", "cannot find", "error:", "failed", "fatal:",
                "unable to", "connection refused", "network is unreachable"
        };
        for (String indicator : errorIndicators) {
            if (lowerOutput.contains(indicator)) {
                return false;
            }
        }
        return true;
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
