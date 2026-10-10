package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.impl;

import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.CommandSafetyPolicy;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import org.springframework.stereotype.Component;

/** 责任链的终端有效性检查节点，执行位置由 getOrder 声明。 */
@Component
public class TerminalAvailabilityCommandSafetyPolicy implements CommandSafetyPolicy {
    private final ISshTerminalService sshTerminalService;

    /** @param sshTerminalService 用于查询请求绑定终端是否仍然存在的领域服务 */
    public TerminalAvailabilityCommandSafetyPolicy(ISshTerminalService sshTerminalService) {
        this.sshTerminalService = sshTerminalService;
    }

    /** @return 终端检查的默认排序值，修改此值即可调整节点顺序 */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 字符串入口没有终端信息，统一按“未绑定终端”处理。
     * @param command 检测命令
     * @return 前置条件失败结果
     */
    @Override
    public CommandSafetyDecision evaluate(String command) {
        return evaluate(CommandSafetyContext.of(command, null, null, "unknown"));
    }

    /**
     * 确认终端已经绑定且尚未关闭；失败时立即结束责任链。
     * @param context 当前命令与终端上下文
     * @return 终端有效时继续，否则返回前置条件失败原因
     */
    @Override
    public CommandSafetyDecision evaluate(CommandSafetyContext context) {
        String terminalId = context.terminalSessionId();
        if (terminalId == null || terminalId.isBlank()) {
            return CommandSafetyDecision.unavailable("TERMINAL_NOT_BOUND",
                    "未绑定 SSH 终端会话。请先打开 SSH 终端连接。", context.normalizedCommand());
        }
        if (!sshTerminalService.sessionExists(terminalId)) {
            return CommandSafetyDecision.unavailable("TERMINAL_NOT_AVAILABLE",
                    "SSH 终端会话不存在或已关闭: " + terminalId, context.normalizedCommand());
        }
        return CommandSafetyDecision.allow(context.normalizedCommand());
    }
}
