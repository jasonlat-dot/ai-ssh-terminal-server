package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj;

import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;

/**
 * 一次命令安全责任链的不可变上下文，独立于 ADK 工具协议。
 *
 * @param command 原始执行命令；检查结束后仍执行此文本
 * @param normalizedCommand 本地规则生成的检测文本，只用于后续安全分析
 * @param terminalSessionId 请求绑定的 SSH 终端 ID
 * @param invocation 当前主/子 Agent 的任务与根意图快照，允许为空
 * @param agentName 实际执行命令的 Agent 名称
 */
public record CommandSafetyContext(
        String command,
        String normalizedCommand,
        String terminalSessionId,
        AgentInvocationContext invocation,
        String agentName
) {
    /** 归一化可空文本，避免各节点重复处理命令和 Agent 名称。 */
    public CommandSafetyContext {
        command = command == null ? "" : command;
        normalizedCommand = normalizedCommand == null ? command : normalizedCommand;
        agentName = agentName == null || agentName.isBlank() ? "unknown" : agentName;
    }

    /**
     * 创建尚未执行本地规范化的检查上下文。
     *
     * @param command 原始命令
     * @param terminalSessionId 当前绑定的终端 ID
     * @param invocation 当前 Agent 上下文
     * @param agentName 当前 Agent 名称
     * @return 本次检查独享的上下文
     */
    public static CommandSafetyContext of(
            String command, String terminalSessionId,
            AgentInvocationContext invocation, String agentName) {
        return new CommandSafetyContext(command, command, terminalSessionId, invocation, agentName);
    }

    /**
     * 将上一节点生成的检测文本传递给下一节点，原始命令保持完整。
     *
     * @param normalizedCommand 已通过当前节点校验的检测文本
     * @return 携带新检测文本的上下文副本
     */
    public CommandSafetyContext withNormalizedCommand(String normalizedCommand) {
        return new CommandSafetyContext(command, normalizedCommand, terminalSessionId, invocation, agentName);
    }
}
