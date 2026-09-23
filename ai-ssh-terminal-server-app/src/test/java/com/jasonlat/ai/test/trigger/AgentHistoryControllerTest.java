package com.jasonlat.ai.test.trigger;

import com.jasonlat.ai.domain.agent.model.entity.ChatMessageEntity;
import com.jasonlat.ai.domain.agent.model.entity.ChatSessionEntity;
import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.trigger.api.dto.ChatMessageResponse;
import com.jasonlat.ai.trigger.api.dto.ChatSessionResponse;
import com.jasonlat.ai.trigger.api.dto.SessionDataRequest;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.trigger.http.AgentController;
import com.jasonlat.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgentHistoryControllerTest {

    private final IChatService chatService = mock(IChatService.class);
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
    }

    @Test
    void refusesToReadMessagesOutsideTheCurrentUsersAgent() {
        Response<List<ChatMessageResponse>> response = controller.queryMessageList(
                "agent-1", "user-1", "other-session", 100);

        assertEquals(ResponseCode.SESSION_NOT_EXIST.getCode(), response.getCode());
        verify(chatService, never()).queryMessageList(anyString(), anyInt());
    }

    @Test
    void returnsOwnedMessagesInRepositoryOrderAndCapsLimit() {
        when(chatService.ownsSession("agent-1", "user-1", "session-1")).thenReturn(true);
        when(chatService.queryMessageList("session-1", 500)).thenReturn(List.of(
                ChatMessageEntity.builder().id(1L).role("user").content("检查 Docker").build(),
                ChatMessageEntity.builder().id(2L).role("assistant").content("运行正常").build()));

        Response<List<ChatMessageResponse>> response = controller.queryMessageList(
                "agent-1", "user-1", "session-1", 999);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(List.of("user", "assistant"), response.getData().stream()
                .map(ChatMessageResponse::role).toList());
    }

    @Test
    void listsRecentSessionsForTheRequestedAgentAndUser() {
        when(chatService.querySessionList("agent-1", "user-1", 50)).thenReturn(List.of(
                ChatSessionEntity.builder().id("session-1").title("检查 Docker").messageCount(2).build()));

        Response<List<ChatSessionResponse>> response = controller.querySessionList(
                "agent-1", "user-1", 999);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("session-1", response.getData().get(0).sessionId());
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
