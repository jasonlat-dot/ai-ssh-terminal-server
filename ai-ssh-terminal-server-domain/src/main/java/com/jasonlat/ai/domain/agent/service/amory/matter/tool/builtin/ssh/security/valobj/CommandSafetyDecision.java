package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj;

import lombok.Getter;
import com.jasonlat.ai.domain.agent.adapter.port.ICommandRiskDecisionPort.CommandRisk;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;

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

    /** 拒绝时 true 表示安全拦截，false 表示前置条件失败；允许时为 false。 */
    private final boolean safetyViolation;

    /** 正式参与拦截的外部判断证据；本地规则和终端检查不携带此字段。 */
    private final StructuredDecision<CommandRisk> semanticDecision;

    /**
     * 由工厂方法构造统一结果，明确区分安全拒绝与普通前置条件失败。
     *
     * @param allowed 是否允许继续执行
     * @param ruleId 首个拒绝节点的稳定编号，允许时为空
     * @param reason 用户可读原因，允许时为空
     * @param normalizedCommand 当前检测文本
     * @param safetyViolation 是否属于安全拦截
     * @param semanticDecision 外部正式风险证据，本地与终端节点为空
     */
    private CommandSafetyDecision(
            boolean allowed,
            String ruleId,
            String reason,
            String normalizedCommand,
            boolean safetyViolation,
            StructuredDecision<CommandRisk> semanticDecision) {
        this.allowed = allowed;
        this.ruleId = ruleId;
        this.reason = reason;
        this.normalizedCommand = normalizedCommand;
        this.safetyViolation = safetyViolation;
        this.semanticDecision = semanticDecision;
    }

    /** 创建允许执行的判定结果。 */
    public static CommandSafetyDecision allow(String normalizedCommand) {
        return new CommandSafetyDecision(true, null, null, normalizedCommand, false, null);
    }

    /** 创建拒绝执行的判定结果。 */
    public static CommandSafetyDecision deny(
            String ruleId,
            String reason,
            String normalizedCommand) {
        return new CommandSafetyDecision(false, ruleId, reason, normalizedCommand, true, null);
    }

    /**
     * 创建执行前置条件失败结果，避免把“终端未连接”显示成风险命令。
     *
     * @param ruleId 前置条件编号
     * @param reason 用户可读的失败原因
     * @param normalizedCommand 检测文本
     * @return 不允许执行、但不属于安全拦截的结果
     */
    public static CommandSafetyDecision unavailable(
            String ruleId, String reason, String normalizedCommand) {
        return new CommandSafetyDecision(false, ruleId, reason, normalizedCommand, false, null);
    }

    /**
     * 将正式 REVIEW/BLOCK 结论包装为统一的安全拒绝结果。
     *
     * @param decision 已达到概率门槛、非影子模式的外部判断
     * @param normalizedCommand 已通过本地规则的检测文本
     * @return 携带审计证据的拒绝结果
     */
    public static CommandSafetyDecision denySemantic(
            StructuredDecision<CommandRisk> decision, String normalizedCommand) {
        if (decision == null || decision.choice() == CommandRisk.ALLOW) {
            throw new IllegalArgumentException("语义拒绝结果必须为 REVIEW 或 BLOCK");
        }
        String reason = decision.choice() == CommandRisk.BLOCK
                ? "命令存在明显高风险、越权风险或与当前任务不一致"
                : "命令具有潜在破坏性、提权操作或授权范围不明确，需要人工确认";
        return new CommandSafetyDecision(false, "SYSTEM_ONE_" + decision.choice().name(),
                reason, normalizedCommand, true, decision);
    }
}
