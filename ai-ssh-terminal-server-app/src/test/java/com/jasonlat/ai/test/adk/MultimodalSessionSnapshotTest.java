package com.jasonlat.ai.test.adk;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.models.LlmRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import com.jasonlat.ai.domain.agent.service.amory.matter.patch.LocalMessageConverter;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** 模拟 Runner 追加用户消息后重新获取 Session；媒体只能在本轮结束后释放。 */
class MultimodalSessionSnapshotTest {
    @Test
    void reloadedSessionKeepsImageAndPdfUntilInvocationFinishes() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session live = service.createSession("app", "alice", new ConcurrentHashMap<>(), "session").blockingGet();
        service.appendEvent(live, mediaEvent()).blockingGet();

        // 不能只断言最初的 live 对象；Runner 实际用再次 getSession 的结果创建模型请求。
        Session reloaded = reload(service);
        var prompt = new LocalMessageConverter(new ObjectMapper()).toLlmPrompt(LlmRequest.builder()
                .model("test-model").tools(Map.of())
                .contents(reloaded.events().stream().map(e -> e.content().orElseThrow()).toList()).build());
        assertEquals(2, prompt.getUserMessage().getMedia().size());
        assertEquals("image/png", prompt.getUserMessage().getMedia().get(0).getMimeType().toString());
        assertEquals("application/pdf", prompt.getUserMessage().getMedia().get(1).getMimeType().toString());

        service.releaseInvocationMedia("app", "alice", "session");
        service.releaseInvocationMedia("app", "alice", "session");
        assertEquals(0, mediaCount(reload(service)));
        // 清理采用新事件，不反向修改本轮模型持有的消息对象。
        assertEquals(2, mediaCount(reloaded));
        assertEquals("analyse", reload(service).events().getFirst().content().orElseThrow()
                .parts().orElseThrow().getFirst().text().orElseThrow());
    }

    @Test
    void lateAppendCannotRestoreReleasedBytesAndNextInvocationCanReadNewMedia() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session live = service.createSession("app", "alice", new ConcurrentHashMap<>(), "session").blockingGet();
        service.appendEvent(live, mediaEvent()).blockingGet();
        service.releaseInvocationMedia("app", "alice", "session");
        service.appendEvent(live, mediaEvent()).blockingGet();
        assertEquals(0, mediaCount(reload(service)));

        service.prepareInvocation("app", "alice", "session", List.of(), new ConcurrentHashMap<>());
        service.appendEvent(reload(service), mediaEvent()).blockingGet();
        assertEquals(2, mediaCount(reload(service)));
        service.releaseInvocationMedia("app", "alice", "session");
        assertEquals(0, mediaCount(reload(service)));
        assertDoesNotThrow(() -> service.releaseInvocationMedia("app", "alice", "missing"));
    }

    @Test
    void releasingOneSessionDoesNotStripAnotherSession() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session first = service.createSession("app", "alice", new ConcurrentHashMap<>(), "session").blockingGet();
        Session second = service.createSession("app", "bob", new ConcurrentHashMap<>(), "session").blockingGet();
        service.appendEvent(first, mediaEvent()).blockingGet();
        service.appendEvent(second, mediaEvent()).blockingGet();
        service.releaseInvocationMedia("app", "alice", "session");
        assertEquals(0, mediaCount(reload(service)));
        assertEquals(2, mediaCount(service.getSession("app", "bob", "session", Optional.empty()).blockingGet()));
    }

    private Event mediaEvent() {
        return Event.builder().id(Event.generateEventId()).author("user")
                .content(Content.builder().role("user").parts(List.of(Part.fromText("analyse"),
                        Part.fromBytes(new byte[]{1, 2, 3}, "image/png"),
                        Part.fromBytes(new byte[]{4, 5, 6}, "application/pdf"))).build()).build();
    }

    private Session reload(CustomAdkSessionService service) {
        Session session = service.getSession("app", "alice", "session", Optional.empty()).blockingGet();
        assertNotNull(session);
        return session;
    }

    private long mediaCount(Session session) {
        return session.events().stream().flatMap(e -> e.content().orElseThrow().parts().orElseThrow().stream())
                .filter(p -> p.inlineData().isPresent() || p.fileData().isPresent()).count();
    }
}
