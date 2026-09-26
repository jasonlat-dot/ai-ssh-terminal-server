package com.jasonlat.ai.test.adk;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** 当前模型调用必须看到媒体，但保存的跨请求快照不能持有二进制正文。 */
class MultimodalSessionSnapshotTest {
    @Test
    void liveSessionKeepsMediaWhileStoredSnapshotDropsBytes() {
        CustomAdkSessionService service = new CustomAdkSessionService();
        Session live = service.createSession("app", "alice", new ConcurrentHashMap<>(), "session").blockingGet();
        Event event = Event.builder().id(Event.generateEventId()).author("user")
                .content(Content.builder().role("user").parts(List.of(
                        Part.fromText("analyse"), Part.fromBytes(new byte[]{1, 2, 3}, "image/png"))).build())
                .build();
        service.appendEvent(live, event).blockingGet();

        assertTrue(live.events().stream().flatMap(e -> e.content().orElseThrow().parts().orElseThrow().stream())
                .anyMatch(p -> p.inlineData().isPresent()));
        Session stored = service.getSession("app", "alice", "session", Optional.empty()).blockingGet();
        assertNotNull(stored);
        assertFalse(stored.events().stream().flatMap(e -> e.content().orElseThrow().parts().orElseThrow().stream())
                .anyMatch(p -> p.inlineData().isPresent()));
    }
}
