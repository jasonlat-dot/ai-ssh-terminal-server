package com.jasonlat.ai.domain.agent.service.context.provider.impl;

import com.jasonlat.ai.domain.agent.service.context.provider.ContextProvider;
import com.jasonlat.ai.domain.agent.service.context.provider.ContextProviderOrder;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import com.jasonlat.ai.types.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端状态上下文提供者（order=10，最先执行）
 * <p>
 * 功能：通过 SSH 终端实时采集远程服务器的环境信息（OS/用户/工作目录/运行时长），
 * 让模型"知道自己在哪台机器上操作"。该逻辑从 PromptService 下沉至此。
 * <p>
 * 运行过程：
 * <pre>
 *   provide(sessionId, userId, terminalSessionId, history)
 *        |
 *        | terminalSessionId 为空 ? --> 返回空 Map（无终端则不采集）
 *        v
 *   safeExec(terminalSessionId, cmd)  逐条执行采集命令
 *        |
 *        +-- "uname -srm"  --> osInfo          （操作系统/架构）
 *        +-- "whoami"      --> currentUser     （当前登录用户）
 *        +-- "pwd"         --> currentDirectory（当前工作目录）
 *        +-- "uptime"      --> uptime          （运行时长）
 *        |
 *        v
 *   Map{osInfo, currentUser, currentDirectory, uptime}
 *        |
 *        v
 *   ChatContextService 合并 --> PromptContextVO --> 消息前缀 [系统环境]
 * </pre>
 * 容错设计：每条命令独立 try-catch（safeExec），单条失败仅该字段留空，
 * 环境采集是"锦上添花"，绝不阻断主流程。
 */
@Component
public class TerminalStateProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(TerminalStateProvider.class);
    @Resource
    private ISshTerminalService sshTerminalService;

    @Override
    public String getName() {
        return "terminal-state";
    }

    @Override
    public int getOrder() {
        return ContextProviderOrder.TERMINAL;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 采集上下文
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史
     * @return 上下文键值对（如 osInfo、toolResultSummary），允许为空
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return result;
        }

        String osInfo = safeExec(terminalSessionId, "uname -srm");
        if (StringUtils.hasText(osInfo)) {
            result.put("osInfo", osInfo);
        }

        String user = safeExec(terminalSessionId, "whoami");
        if (StringUtils.hasText(user)) {
            result.put("currentUser", user);
        }

        String pwd = safeExec(terminalSessionId, "pwd");
        if (StringUtils.hasText(pwd)) {
            result.put("currentDirectory", pwd);
        }

        String uptime = safeExec(terminalSessionId, "uptime -p 2>/dev/null || uptime");
        if (StringUtils.hasText(uptime)) {
            result.put("uptime", uptime);
        }

        return result;
    }

    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String res = sshTerminalService.executeCommand(terminalSessionId, cmd);
            return res != null ? res.trim() : "";
        } catch (Exception e) {
            log.debug("terminalSessionId:{} command:{} 执行失败，原因：", terminalSessionId, cmd, e);
            return "";
        }
    }
}
