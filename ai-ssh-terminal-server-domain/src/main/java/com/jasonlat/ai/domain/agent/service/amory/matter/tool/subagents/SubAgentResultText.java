package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.List;

/** 从子 Runner 的流式事件中还原工具执行后的最后一段模型回复。 */
public final class SubAgentResultText {

    private SubAgentResultText() {
    }

    public static String finalReply(List<Event> events, String agentName) {
        StringBuilder reply = new StringBuilder();
        for (Event event : events) {
            if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
                // 新一轮模型调用由工具结果触发，旧的“准备执行”文本不是最终结论。
                reply.setLength(0);
            }
            if (event.content().isEmpty()) {
                continue;
            }
            Content content = event.content().get();
            String role = content.role().orElse("");
            if (!("model".equals(role) || "assistant".equals(role)
                    || (role.isBlank() && agentName.equals(event.author())))) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (Part part : content.parts().orElse(List.of())) {
                part.text().ifPresent(text::append);
            }
            if (text.isEmpty()) {
                continue;
            }
            String chunk = text.toString();
            String previous = reply.toString();
            if (!event.partial().orElse(false) && chunk.startsWith(previous) && !previous.isEmpty()) {
                reply.append(chunk.substring(previous.length()));
            } else if (event.partial().orElse(false) || !previous.startsWith(chunk)) {
                reply.append(chunk);
            }
        }
        return reply.toString();
    }
}
