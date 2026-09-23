package com.jasonlat.ai.test.domain.agent;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.jasonlat.ai.domain.agent.service.intent.classifier.node.LLMIntentClassifierNode;
import com.jasonlat.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentEntityNormalizationTest {

    @Test
    void shouldNormalizeCommandArrayAndOtherEntityValuesBeforeBuildingPrompt() {
        IntentResultVO result = parse("""
                {"intent":"EXECUTE","confidence":0.95,
                 "entities":{"commands":["df -h","docker ps"],"parallel":true,"count":2},
                 "candidates":["MONITOR"]}
                """);

        assertEquals(IntentTypeEnumVO.EXECUTE, result.getIntent());
        assertEquals("[\"df -h\",\"docker ps\"]", result.getEntities().get("commands"));
        assertEquals("true", result.getEntities().get("parallel"));
        assertEquals("2", result.getEntities().get("count"));
        assertEquals(List.of(IntentTypeEnumVO.MONITOR), result.getCandidateIntents());

        String prompt = new DynamicPromptBuilder().buildEphemeralContext(
                PromptContextVO.builder().intentResult(result).build());
        assertTrue(prompt.contains("commands=[\"df -h\",\"docker ps\"]"));
    }

    @Test
    void shouldKeepStringEntitiesAndIgnoreNonObjectEntities() {
        IntentResultVO normal = parse("""
                {"intent":"MONITOR","entities":{"service":"docker"}}
                """);
        assertEquals(Map.of("service", "docker"), normal.getEntities());

        IntentResultVO unexpectedShape = parse("""
                {"intent":"EXECUTE","entities":["df -h","docker ps"]}
                """);
        assertEquals(IntentTypeEnumVO.EXECUTE, unexpectedShape.getIntent());
        assertTrue(unexpectedShape.getEntities().isEmpty());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRenderLegacyCachedEntityListWithoutClassCastException() {
        Map<String, String> legacyEntities = (Map) Map.of(
                "commands", List.of("df -h", "docker ps"));
        IntentResultVO legacyResult = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.EXECUTE)
                .confidence(0.95)
                .entities(legacyEntities)
                .build();

        String prompt = new DynamicPromptBuilder().buildEphemeralContext(
                PromptContextVO.builder().intentResult(legacyResult).build());
        assertTrue(prompt.contains("commands=[df -h, docker ps]"));
    }

    private IntentResultVO parse(String response) {
        return ReflectionTestUtils.invokeMethod(
                new LLMIntentClassifierNode(), "parseResponse", response);
    }
}
