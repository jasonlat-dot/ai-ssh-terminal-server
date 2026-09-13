package com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl;


import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * SSH 命令执行 ADK 工具，为智能体提供在 SSH 终端执行命令的能力
 * <p>
 * 使用 ADK 的 @Schema 注解定义参数，支持 FunctionTool.create()
 */
@Slf4j
@Service("sshExecuteAdkTool")
public class SshExecuteAdkTool implements AdkToolProvider {

    /**
     * SSH 工具执行事件。Controller 使用它向当前浏览器推送“执行中/成功/失败”状态。
     * completed=false 表示工具刚开始；completed=true 表示 result 已经可用。
     */
    public record ToolExecutionEvent(String id,
                                     String toolName,
                                     String command,
                                     Map<String, Object> result,
                                     boolean completed,
                                     boolean success) {
    }

    /**
     * terminalSessionId -> 当前 SSE 请求的工具事件监听器。
     * 使用终端会话 ID 隔离不同浏览器和服务器，避免一个用户看到另一个会话的工具事件。
     */
    private static final ConcurrentMap<String, Consumer<ToolExecutionEvent>> executionListeners =
            new ConcurrentHashMap<>();

    @Resource
    private ISshTerminalService sshTerminalService;

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

    /**
     * 为一次 Agent SSE 对话注册 SSH 工具执行监听器。
     * 返回的 AutoCloseable 必须在 SSE 完成、超时或断开时关闭，防止监听器泄漏。
     */
    public static AutoCloseable observeExecutions(String terminalSessionId,
                                                   Consumer<ToolExecutionEvent> listener) {
        executionListeners.put(terminalSessionId, listener);
        return () -> executionListeners.remove(terminalSessionId, listener);
    }

    private static void publishExecution(String terminalSessionId, ToolExecutionEvent event) {
        if (terminalSessionId == null) return;
        Consumer<ToolExecutionEvent> listener = executionListeners.get(terminalSessionId);
        if (listener != null) listener.accept(event);
    }

    @Override
    public List<FunctionTool> getTools() {
        return List.of(FunctionTool.create(this, "executeCommand"));
    }

    public Map<String, Object> executeCommand(
            @Annotations.Schema(name = "command", description = "要执行的 Shell 命令，如: ls -la, apt install docker.io, docker --version")
            String command) {

        // 优先从 ThreadLocal 获取，支持异步线程继承
        String terminalSessionId = currentTerminalSession.get();

        // ThreadLocal 为空时回退到会话级变量（线程池场景下 ThreadLocal 可能失效）
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            terminalSessionId = sessionTerminalSessionId;
            log.info("[executeCommand] ThreadLocal 为空，回退到会话级变量: terminalSessionId={}", terminalSessionId);
        }

        String toolCallId = "ssh_" + UUID.randomUUID();
        publishExecution(terminalSessionId, new ToolExecutionEvent(
                toolCallId, "executeCommand", command, Map.of(), false, true
        ));

        log.info("[executeCommand] thread={}, terminalSessionId={}, command={}",
                Thread.currentThread().getName(), terminalSessionId, command);

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            log.warn("[executeCommand] 终端会话ID为空，无法执行命令");
            return completeExecution(terminalSessionId, toolCallId, command, Map.of(
                    "success", false,
                    "output", "未绑定 SSH 终端会话。请先打开 SSH 终端连接。",
                    "command", command
            ));
        }

        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            log.warn("[executeCommand] 终端会话不存在: {}", terminalSessionId);
            return completeExecution(terminalSessionId, toolCallId, command, Map.of(
                    "success", false,
                    "output", "SSH 终端会话不存在或已关闭: " + terminalSessionId,
                    "command", command
            ));
        }

        try {
            log.info("SSH 执行命令: session={}, command={}", terminalSessionId, command);

            // 执行命令
            String output = sshTerminalService.executeCommand(terminalSessionId, command);

            log.info("SSH 命令执行完成: outputLength={}, output={}",
                    output.length(), output.length() > 300 ? output.substring(0, 300) + "..." : output);

            // 分析输出，判断是否成功
            boolean success = isExecutionSuccessful(output);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("command", command);
            result.put("output", output);
            result.put("success", success);

            if (!success) {
                result.put("suggestion", analyzeError(output));
            }

            return completeExecution(terminalSessionId, toolCallId, command, result);

        } catch (Exception e) {
            log.error("SSH 命令执行异常: session={}, command={}", terminalSessionId, command, e);
            return completeExecution(terminalSessionId, toolCallId, command, Map.of(
                    "success", false,
                    "output", "命令执行异常: " + e.getMessage(),
                    "command", command
            ));
        }

    }

    /** 发布完成事件并原样返回工具结果，确保所有返回分支都能更新前端工具卡片。 */
    private Map<String, Object> completeExecution(String terminalSessionId,
                                                  String toolCallId,
                                                  String command,
                                                  Map<String, Object> result) {
        boolean success = !Boolean.FALSE.equals(result.get("success"));
        publishExecution(terminalSessionId, new ToolExecutionEvent(
                toolCallId, "executeCommand", command, result, true, success
        ));
        return result;
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
