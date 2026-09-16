package com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl;


import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.security.CommandSafetyPolicy;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SSH 命令执行 ADK 工具，为智能体提供在 SSH 终端执行命令的能力
 * <p>
 * 使用 ADK 的 @Schema 注解定义参数，支持 FunctionTool.create()
 */
@Slf4j
@Service("sshExecuteAdkTool")
public class SshExecuteAdkTool implements AdkToolProvider {

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private CommandSafetyPolicy commandSafetyPolicy;

    // 当前线程的终端会话 ID（使用 InheritableThreadLocal 支持子线程继承）
    private static final InheritableThreadLocal<String> currentTerminalSession = new InheritableThreadLocal<>();

    /** 当前会话级终端会话ID（由 Controller 设置，优先级低于 ThreadLocal） */
    private static volatile String sessionTerminalSessionId;
    /**
     * 设置当前线程的终端会话 ID（兼容旧接口）
     */
    public static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
        sessionTerminalSessionId = terminalSessionId;
        log.info("[ThreadLocal] 设置终端会话: thread={}, terminalSession={}",
                Thread.currentThread().getName(), terminalSessionId);
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    public static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
    }

    @Override
    public List<FunctionTool> getTools() {
        return List.of(FunctionTool.create(this, "executeCommand"));
    }

    public Map<String, Object> executeCommand(
            @Annotations.Schema(name = "command", description = "要执行的 Shell 命令，如: ls -la, apt install docker.io, docker --version")
            String command) {

        // AI 工具命令的统一安全入口：任何命令都必须先通过后端策略，不能依赖模型自行判断。
        String safeCommand = command == null ? "" : command;
        CommandSafetyDecision safetyDecision = commandSafetyPolicy.evaluate(safeCommand);
        if (!safetyDecision.isAllowed()) {
            // 返回结构化 ruleId/riskLevel，便于工具结果、日志和前端使用同一个拦截原因。
            log.warn("SSH 命令被安全策略拦截 ruleId={}, reason={}, command={}",
                    safetyDecision.getRuleId(), safetyDecision.getReason(), safeCommand);
            return Map.of(
                    "success", false,
                    "blocked", true,
                    "ruleId", safetyDecision.getRuleId(),
                    "riskLevel", "DENIED",
                    "output", "⚠️ 命令已被安全策略拦截：" + safetyDecision.getReason()
                            + "\n如确认必须执行，请登录终端后人工操作。",
                    "command", safeCommand
            );
        }

        // 优先从 ThreadLocal 获取，支持异步线程继承
        String terminalSessionId = currentTerminalSession.get();
        // ThreadLocal 为空时回退到会话级变量（线程池场景下 ThreadLocal 可能失效）
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            terminalSessionId = sessionTerminalSessionId;
            log.info("[executeCommand] ThreadLocal 为空，回退到会话级变量: terminalSessionId={}", terminalSessionId);
        }
        log.info("[executeCommand] thread={}, terminalSessionId={}, command={}",
                Thread.currentThread().getName(), terminalSessionId, safeCommand);

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            log.warn("[executeCommand] 终端会话ID为空，无法执行命令");
            return Map.of(
                    "success", false,
                    "output", "未绑定 SSH 终端会话。请先打开 SSH 终端连接。",
                    "command", safeCommand
            );
        }

        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            log.warn("[executeCommand] 终端会话不存在: {}", terminalSessionId);
            return Map.of(
                    "success", false,
                    "output", "SSH 终端会话不存在或已关闭: " + terminalSessionId,
                    "command", safeCommand
            );
        }

        try {
            log.info("SSH 执行命令: session={}, command={}", terminalSessionId, safeCommand);

            // 执行命令
            String output = sshTerminalService.executeCommand(terminalSessionId, safeCommand);

            log.info("SSH 命令执行完成: outputLength={}, output={}",
                    output.length(), output.length() > 300 ? output.substring(0, 300) + "..." : output);

            // 分析输出，判断是否成功
            boolean success = isExecutionSuccessful(output);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("command", safeCommand);
            result.put("output", output);
            result.put("success", success);

            if (!success) {
                result.put("suggestion", analyzeError(output));
            }

            return result;
        } catch (Exception e) {
            log.error("SSH 命令执行异常: session={}, command={}", terminalSessionId, safeCommand, e);
            return Map.of(
                    "success", false,
                    "output", "命令执行异常: " + e.getMessage(),
                    "command", safeCommand
            );
        }

    }


    /**
     * 判断命令执行是否成功
     */
    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }

        String lowerOutput = output.toLowerCase();
        String[] errorIndicators = {
                "命令退出码:",
                "command not found", "no such file or directory", "permission denied",
                "operation not permitted", "cannot find", "error:", "failed",
                "fatal:", "unable to", "connection refused", "network is unreachable"
        };

        for (String indicator : errorIndicators) {
            if (lowerOutput.contains(indicator)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分析错误并提供解决建议
     */
    private String analyzeError(String output) {
        if (output == null) return null;

        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。建议检查命令名称或安装对应软件包。";
        }
        if (lowerOutput.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lowerOutput.contains("connection refused") || lowerOutput.contains("network is unreachable")) {
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        }
        return "执行失败，请检查命令和输出信息。";
    }


}
