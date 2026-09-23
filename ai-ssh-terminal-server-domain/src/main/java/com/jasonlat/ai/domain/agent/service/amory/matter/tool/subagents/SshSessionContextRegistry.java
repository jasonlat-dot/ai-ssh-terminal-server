package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents;

import com.google.adk.tools.ToolContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务会话与 SSH 终端会话的运行时绑定。
 * <p>
 * ADK 子 Runner 的工具调用可能运行在不同线程，且部分版本不会注入 ToolContext，
 * 因此派发子 Agent 前必须通过显式上下文恢复终端绑定。
 */
public final class SshSessionContextRegistry {

    /**
     * 最近一次发起流式请求使用的终端，仅作为无法关联业务会话时的最后兜底。
     */
    private static volatile String activeTerminalSessionId;

    private SshSessionContextRegistry() {
    }

    /**
     * 记录最近一次流式请求使用的 SSH 终端。
     */
    public static void setActive(String terminalSessionId) {
        activeTerminalSessionId = terminalSessionId;
    }

    /**
     * 查询最近一次流式请求使用的 SSH 终端；不存在时返回空 Optional。
     */
    public static Optional<String> active() {
        return Optional.ofNullable(activeTerminalSessionId);
    }

}
