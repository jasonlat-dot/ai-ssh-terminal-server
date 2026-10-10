package com.jasonlat.ai.test.trigger;

import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.trigger.api.dto.SessionDataRequest;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.trigger.http.AgentController;
import com.jasonlat.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgentStopChatControllerTest {

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController();
    }

    @Test
    void stopsOnlyTheRequestedActiveSession() {
        IAIAgentReActServiceCase execution = mock(IAIAgentReActServiceCase.class);
        ReflectionTestUtils.setField(controller, "agentReActServiceCase", execution);
        when(execution.stopChat("agent-1", "user-1", "session-1")).thenReturn(true);
        SessionDataRequest request = new SessionDataRequest();
        request.setAgentId("agent-1");
        request.setUserId("user-1");
        request.setSessionId("session-1");

        Response<Boolean> response = controller.stopChat(request);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(true, response.getData());
    }
}
