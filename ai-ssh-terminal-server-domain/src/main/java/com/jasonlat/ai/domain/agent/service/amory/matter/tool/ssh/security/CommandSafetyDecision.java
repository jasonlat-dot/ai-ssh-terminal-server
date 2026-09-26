package com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh.security;

import lombok.Getter;

/**
 * 命令安全策略的不可变判定结果。
 *
 * <p>允许时 {@code ruleId} 和 {@code reason} 为空；拒绝时二者用于审计日志、
 * 工具返回值以及前端提示。{@code normalizedCommand} 仅用于安全分析和排查，
 * 不应替代用户原始命令直接执行。</p>
 */
@Getter
public final class CommandSafetyDecision {

    /** 是否允许进入 SSH 执行通道。 */
    private final boolean allowed;

    /** 命中的稳定规则编号，例如 RECURSIVE_FORCE_DELETE。 */
    private final String ruleId;

    /** 面向用户和日志的拒绝原因。 */
    private final String reason;

    /** 经 Unicode、换行续写和空白规范化后的检测文本。 */
    private final String normalizedCommand;

    private CommandSafetyDecision(
            boolean allowed,
            String ruleId,
            String reason,
            String normalizedCommand) {
        this.allowed = allowed;
        this.ruleId = ruleId;
        this.reason = reason;
        this.normalizedCommand = normalizedCommand;
    }

    /** 创建允许执行的判定结果。 */
    public static CommandSafetyDecision allow(String normalizedCommand) {
        return new CommandSafetyDecision(true, null, null, normalizedCommand);
    }

    /** 创建拒绝执行的判定结果。 */
    public static CommandSafetyDecision deny(
            String ruleId,
            String reason,
            String normalizedCommand) {
        return new CommandSafetyDecision(false, ruleId, reason, normalizedCommand);
    }
}
