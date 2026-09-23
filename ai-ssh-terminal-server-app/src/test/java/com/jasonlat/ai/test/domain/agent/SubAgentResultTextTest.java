package com.jasonlat.ai.test.domain.agent;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.SubAgentResultText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubAgentResultTextTest {

    @Test
    void returnsStreamedReplyAfterToolWithoutRepeatingFinalSnapshot() {
        List<Event> events = List.of(
                modelText("我来检查。", true),
                toolResponse(),
                modelText("Docker ", true),
                modelText("已安装。", true),
                modelText("Docker 已安装。", false),
                Event.builder().id(Event.generateEventId()).author("sshAgent").build());

        assertEquals("Docker 已安装。", SubAgentResultText.finalReply(events, "sshAgent"));
    }

    private Event modelText(String text, boolean partial) {
        return Event.builder()
                .id(Event.generateEventId())
                .author("sshAgent")
                .partial(partial)
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .build();
    }

    private Event toolResponse() {
        return Event.builder()
                .id(Event.generateEventId())
                .author("executeCommand")
                .content(Content.fromParts(Part.builder().functionResponse(FunctionResponse.builder()
                        .id("call-1")
                        .name("executeCommand")
                        .response(Map.of("output", "Docker version 26", "success", true))
                        .build()).build()))
                .build();
    }
}
