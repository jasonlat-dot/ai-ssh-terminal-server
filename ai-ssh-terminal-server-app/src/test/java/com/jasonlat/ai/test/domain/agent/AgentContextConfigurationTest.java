package com.jasonlat.ai.test.domain.agent;

import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import com.jasonlat.ai.domain.agent.service.context.reducer.impl.HybridReducer;
import com.jasonlat.ai.domain.agent.service.context.reducer.impl.PriorityReducer;
import com.jasonlat.ai.domain.agent.service.context.reducer.impl.SlidingWindowReducer;
import com.jasonlat.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextConfigurationTest {

    @Test
    void shouldUseConfiguredMilestonePriorityAndContentLength() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.getMilestone().setMaxContentLength(4);

        AgentContextProperties.MilestoneRule lowerPriorityRule = milestoneRule(
                "lower-priority",
                "user",
                MilestoneVO.Type.USER_CORRECTION,
                10,
                "改一下"
        );
        AgentContextProperties.MilestoneRule higherPriorityRule = milestoneRule(
                "higher-priority",
                "user",
                MilestoneVO.Type.TASK_CHANGE,
                100,
                "改一下"
        );
        properties.getMilestone().setRules(List.of(lowerPriorityRule, higherPriorityRule));

        ConversationContextStore store = new ConversationContextStore();
        MilestoneTracker tracker = new MilestoneTracker(store, properties);
        tracker.detectAndRecord("session-1", "USER", "请改一下这个配置");

        List<MilestoneVO> milestones = store.getRecentMilestones("session-1", 10);
        assertEquals(1, milestones.size());
        assertEquals(MilestoneVO.Type.TASK_CHANGE, milestones.get(0).getType());
        assertEquals("请改一下...", milestones.get(0).getContent());
    }

    @Test
    void shouldStopRecordingMilestonesWhenDisabled() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.getMilestone().setEnabled(false);

        ConversationContextStore store = new ConversationContextStore();
        MilestoneTracker tracker = new MilestoneTracker(store, properties);
        tracker.detectAndRecord("session-1", "tool", "ERROR: permission denied");

        assertTrue(store.getRecentMilestones("session-1", 10).isEmpty());
    }

    @Test
    void shouldKeepConfiguredNumberOfRecentMessages() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.getReducer().setMinimumRecentMessages(3);

        PriorityReducer reducer = new PriorityReducer(properties);
        List<Map<String, Object>> messages = List.of(
                message("user", "first"),
                message("assistant", "second"),
                message("user", "third"),
                message("assistant", "fourth")
        );

        List<Map<String, Object>> reduced = reducer.reduce(messages, 0);

        assertEquals(3, reduced.size());
        assertEquals("second", reduced.get(0).get("content"));
        assertEquals("fourth", reduced.get(2).get("content"));
    }

    @Test
    void shouldSkipOversizedSuccessfulToolAndKeepCompactConversationMessages() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.getReducer().setMinimumRecentMessages(3);

        PriorityReducer reducer = new PriorityReducer(properties);
        List<Map<String, Object>> messages = List.of(
                message("user", "docker status"),
                message("assistant", "docker is running"),
                toolMessage("call-1", "x".repeat(4_000)),
                message("user", "disk status")
        );

        List<Map<String, Object>> reduced = reducer.reduce(messages, 20);

        assertEquals(3, reduced.size());
        assertEquals(List.of("user", "assistant", "user"),
                reduced.stream().map(item -> String.valueOf(item.get("role"))).toList());
        assertFalse(reduced.stream().anyMatch(item -> "tool".equals(item.get("role"))));
    }

    @Test
    void shouldContinueSlidingPastOversizedToolResult() {
        SlidingWindowReducer reducer = new SlidingWindowReducer();
        List<Map<String, Object>> messages = List.of(
                message("assistant", "docker is running"),
                toolMessage("call-1", "x".repeat(4_000)),
                message("user", "disk status")
        );

        List<Map<String, Object>> reduced = reducer.reduce(messages, 20);

        assertEquals(2, reduced.size());
        assertEquals("assistant", reduced.get(0).get("role"));
        assertEquals("user", reduced.get(1).get("role"));
    }

    @Test
    void shouldCompactToolOutputWithoutMutatingOriginalHistory() {
        AgentContextProperties properties = new AgentContextProperties();
        properties.getReducer().setMinimumRecentMessages(4);
        properties.getReducer().setMaxToolResultCharacters(60);

        PriorityReducer priorityReducer = new PriorityReducer(properties);
        SlidingWindowReducer slidingWindowReducer = new SlidingWindowReducer();
        HybridReducer hybridReducer = new HybridReducer();
        ReflectionTestUtils.setField(hybridReducer, "priorityReducer", priorityReducer);
        ReflectionTestUtils.setField(hybridReducer, "slidingReducer", slidingWindowReducer);
        ReflectionTestUtils.setField(hybridReducer, "contextProperties", properties);

        Map<String, Object> originalToolMessage = toolMessage("call-1", "a".repeat(200));
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                message("user", "docker status"),
                message("assistant", "docker is running"),
                originalToolMessage,
                message("user", "disk status")
        ));

        List<Map<String, Object>> reduced = hybridReducer.reduce(messages, 1_000);
        Map<String, Object> compactedToolMessage = reduced.stream()
                .filter(item -> "tool".equals(item.get("role")))
                .findFirst()
                .orElseThrow();

        assertTrue(String.valueOf(compactedToolMessage.get("content")).length() <= 60);
        assertTrue(String.valueOf(compactedToolMessage.get("content")).contains("工具输出已裁剪"));
        assertEquals(200, String.valueOf(originalToolMessage.get("content")).length());
    }

    private AgentContextProperties.MilestoneRule milestoneRule(
            String id,
            String role,
            MilestoneVO.Type type,
            int priority,
            String pattern) {

        AgentContextProperties.MilestoneRule rule = new AgentContextProperties.MilestoneRule();
        rule.setId(id);
        rule.setRole(role);
        rule.setType(type);
        rule.setPriority(priority);
        rule.setPattern(pattern);
        return rule;
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content);
        return message;
    }
}
