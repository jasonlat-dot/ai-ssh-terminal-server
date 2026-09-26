package com.jasonlat.ai.domain.agent.service.amory.matter.tool;

import com.google.adk.tools.BaseTool;

import java.util.List;

public interface AdkToolProvider {

    String TERMINAL_SESSION_STATE_KEY = "terminalSessionId";

    String USER_ID_KEY = "ssh-user-id";

    String AGENT_ID_KEY = "ssh-agent-id";

    String PARENT_SESSION_ID = "ssh-parent-session-id";

    String RUNNER_AGENT_NAME = "ssh-runner-agent-name";

    /** 当前子 Agent 派发实例的 ID，用于把它执行的工具归到同一张前端卡片。 */
    String NESTED_AGENT_CALL_ID = "ssh-nested-agent-call-id";

    /** 发起本次派发的父 Agent 工具调用 ID。 */
    String PARENT_TOOL_CALL_ID = "ssh-parent-tool-call-id";

    /** 本次 HTTP 对话专属的取消对象，子 Runner 和 SSH 工具必须原样透传。 */
    String RUN_CANCELLATION = "ssh-run-cancellation";

    List<? extends BaseTool> getAdkTool();
}
