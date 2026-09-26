package com.jasonlat.ai.test.domain.agent;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.SshExecuteAdkTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomAdkSessionServiceTest {

    @Test
    void shouldAppendFunctionResponseToLiveSessionAndKeepItsStructureInSnapshot() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session session = createSession(service);

        service.appendEvent(session, userEvent("查询 docker 状态")).blockingGet();
        service.appendEvent(session, functionResponseEvent("call-1", "x".repeat(2_000))).blockingGet();

        // ADK 在同一次 invocation 中读取的是这个 live Session。
        assertEquals(2, session.events().size());
        assertEquals("call-1", session.events().get(1).functionResponses().get(0).id().orElseThrow());
        String liveOutput = String.valueOf(session.events().get(1)
                .functionResponses().get(0)
                .response().orElseThrow()
                .get("output"));
        assertEquals(2_000, liveOutput.length());

        List<Event> storedEvents = service.listEvents("app", "user", "session-1")
                .blockingGet()
                .events();
        assertEquals(2, storedEvents.size());
        FunctionResponse storedResponse = storedEvents.get(1).functionResponses().get(0);
        assertEquals("call-1", storedResponse.id().orElseThrow());
        String storedOutput = String.valueOf(storedResponse.response().orElseThrow().get("output"));
        assertTrue(storedOutput.length() <= 1_027);
    }

    @Test
    void shouldNotCountFunctionResponsesAsUserTurns() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session session = createSession(service);

        service.appendEvent(session, userEvent("检查服务")).blockingGet();
        for (int i = 1; i <= 5; i++) {
            service.appendEvent(session, functionResponseEvent("call-" + i, "ok")).blockingGet();
        }

        List<Event> storedEvents = service.listEvents("app", "user", "session-1")
                .blockingGet()
                .events();
        assertEquals(6, storedEvents.size());
        assertEquals("检查服务", storedEvents.get(0).stringifyContent());
        assertEquals("call-1", storedEvents.get(1).functionResponses().get(0).id().orElseThrow());
    }

    @Test
    void shouldNotPersistPartialStreamingEvents() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session session = createSession(service);
        Event partial = Event.builder()
                .author("sshOperator")
                .partial(true)
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText("partial")))
                        .build())
                .build();

        service.appendEvent(session, partial).blockingGet();

        assertTrue(session.events().isEmpty());
        assertTrue(service.listEvents("app", "user", "session-1")
                .blockingGet()
                .events()
                .isEmpty());
    }

    @Test
    void shouldRebuildInvocationFromBusinessHistoryAndBindTerminalState() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        createSession(service);

        service.prepareInvocation(
                "app",
                "user",
                "session-1",
                List.of(
                        Map.of("role", "user", "content", "检查 Docker"),
                        Map.of("role", "tool", "content", "工具原文不应作为孤立响应投影"),
                        Map.of("role", "assistant", "content", "Docker 正常")
                ),
                "terminal-A"
        );

        Session invocationSession = service.getSession("app", "user", "session-1", Optional.empty())
                .blockingGet();
        assertEquals("terminal-A", invocationSession.state().get(
                SshExecuteAdkTool.TERMINAL_SESSION_STATE_KEY));
        assertEquals(2, invocationSession.events().size());
        assertEquals("检查 Docker", invocationSession.events().get(0).stringifyContent());
        assertEquals("Docker 正常", invocationSession.events().get(1).stringifyContent());
    }

    @Test
    void shouldIsolateTerminalStateBetweenConcurrentSessions() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        createSession(service, "session-A");
        createSession(service, "session-B");

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                service.prepareInvocation("app", "user", "session-A", List.of(), "terminal-A"));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
                service.prepareInvocation("app", "user", "session-B", List.of(), "terminal-B"));
        CompletableFuture.allOf(first, second).join();

        Session sessionA = service.getSession("app", "user", "session-A", Optional.empty()).blockingGet();
        Session sessionB = service.getSession("app", "user", "session-B", Optional.empty()).blockingGet();
        assertEquals("terminal-A", sessionA.state().get(SshExecuteAdkTool.TERMINAL_SESSION_STATE_KEY));
        assertEquals("terminal-B", sessionB.state().get(SshExecuteAdkTool.TERMINAL_SESSION_STATE_KEY));
    }

    private Session createSession(CustomAdkSessionService service) {
        return createSession(service, "session-1");
    }

    private Session createSession(CustomAdkSessionService service, String sessionId) {
        return service.createSession(
                        "app",
                        "user",
                        new ConcurrentHashMap<>(),
                        sessionId
                )
                .blockingGet();
    }

    private Event userEvent(String text) {
        return Event.builder()
                .author("user")
                .content(Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText(text)))
                        .build())
                .build();
    }

    private Event functionResponseEvent(String id, String output) {
        FunctionResponse response = FunctionResponse.builder()
                .id(id)
                .name("executeCommand")
                .response(Map.of(
                        "success", true,
                        "output", output
                ))
                .build();

        return Event.builder()
                .author("sshOperator")
                // ADK 使用 user role 承载 FunctionResponse。
                .content(Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().functionResponse(response).build()))
                        .build())
                .build();
    }
}
