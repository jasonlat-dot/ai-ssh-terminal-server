package com.jasonlat.ai.domain.agent.service.amory.matter.tool;

import com.google.adk.tools.BaseTool;

import java.util.List;

public interface AdkToolProvider {

    String TERMINAL_SESSION_STATE_KEY = "terminalSessionId";

    String USER_ID_KEY = "ssh-user-id";

    String AGENT_ID_KEY = "ssh-agent-id";

    String PARENT_SESSION_ID = "ssh-parent-session-id";

    List<? extends BaseTool> getTools();
}
