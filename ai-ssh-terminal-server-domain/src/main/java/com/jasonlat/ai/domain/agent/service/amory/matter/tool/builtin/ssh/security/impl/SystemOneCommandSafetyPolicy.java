package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.impl;

import com.jasonlat.ai.domain.agent.adapter.port.ICommandRiskDecisionPort;
import com.jasonlat.ai.domain.agent.adapter.port.ICommandRiskDecisionPort.CommandRisk;
import com.jasonlat.ai.domain.agent.adapter.port.ICommandRiskDecisionPort.CommandRiskDecisionRequest;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentInvocationContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.CommandSafetyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Jev/Laya 语义风险责任链节点。
 * <p>端口负责供应商协议与概率门槛；本节点负责把业务结论转成统一安全结果。
 * 该节点只接收已经通过前序检查的命令，辅助服务不可用时保留前序允许结论。</p>
 */
@Slf4j
@Component
public class SystemOneCommandSafetyPolicy implements CommandSafetyPolicy {
    private final ICommandRiskDecisionPort commandRiskDecisionPort;

    /** @param commandRiskDecisionPort 供应商无关的 SSH 语义风险判断端口 */
    public SystemOneCommandSafetyPolicy(ICommandRiskDecisionPort commandRiskDecisionPort) {
        this.commandRiskDecisionPort = commandRiskDecisionPort;
    }

    /** @return 语义风险节点默认在普通扩展节点之后执行 */
    @Override
    public int getOrder() {
        return 200;
    }

    /**
     * 兼容仅提供命令的独立调用；完整任务判断请使用上下文重载。
     * @param command 检测文本
     * @return 当前节点的风险结论
     */
    @Override
    public CommandSafetyDecision evaluate(String command) {
        return evaluate(CommandSafetyContext.of(command, null, null, "unknown"));
    }

    /**
     * 结合当前主/子 Agent 的任务判断命令风险。
     * @param context 已通过本地与终端检查的上下文
     * @return 正式 REVIEW/BLOCK 时拒绝，ALLOW 或辅助服务不可用时继续
     */
    @Override
    public CommandSafetyDecision evaluate(CommandSafetyContext context) {
        Optional<StructuredDecision<CommandRisk>> decision;
        AgentInvocationContext invocation = context.invocation();
        try {
            /*
             * 发送实际将执行的原始命令，避免本地空白/Unicode 规范化改变引号内文本后影响语义判断。
             * 子 Agent 使用自己的委派任务；固定禁止项与确认边界由适配器单独提供，任务本身不充当安全策略。
             */
            decision = commandRiskDecisionPort.assess(new CommandRiskDecisionRequest(
                    context.command(),
                    invocation == null ? "" : invocation.currentTaskMessage(),
                    context.agentName(),
                    invocation == null ? null : invocation.rootIntent()));
        } catch (RuntimeException exception) {
            // 辅助服务异常仅降级当前节点；责任链仍会执行后续节点。
            log.warn("SSH 命令语义风险判断异常，回退本地策略 command={} agentName={} exception={} message={}",
                    truncateForLog(context.command()), context.agentName(),
                    exception.getClass().getSimpleName(), exception.getMessage());
            log.debug("SSH 命令语义风险判断异常详情", exception);
            return CommandSafetyDecision.allow(context.normalizedCommand());
        }
        if (decision.isEmpty() || decision.get().choice() == CommandRisk.ALLOW) {
            return CommandSafetyDecision.allow(context.normalizedCommand());
        }
        return CommandSafetyDecision.denySemantic(decision.get(), context.normalizedCommand());
    }

    /** @param command 原始命令 @return 最多 512 字符的审计文本 */
    private String truncateForLog(String command) {
        return command.length() <= 512 ? command : command.substring(0, 512);
    }
}
