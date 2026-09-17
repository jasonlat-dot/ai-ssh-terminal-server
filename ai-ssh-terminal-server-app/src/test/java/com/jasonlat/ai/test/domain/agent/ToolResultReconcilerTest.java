package com.jasonlat.ai.test.domain.agent;

import com.jasonlat.ai.cases.react.model.ToolResultReconciler;
import com.jasonlat.ai.trigger.api.dto.ToolCallDTO;
import com.jasonlat.ai.trigger.api.dto.ToolResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultReconcilerTest {

    @Test
    void shouldMatchSingleToolById() {
        ToolResultReconciler.Reconciliation result = ToolResultReconciler.reconcile(
                List.of(call("call-1")), List.of(toolResult("call-1")));

        assertEquals(1, result.matched().size());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void shouldMatchMultipleToolsWithoutDependingOnOrder() {
        ToolResultReconciler.Reconciliation result = ToolResultReconciler.reconcile(
                List.of(call("call-1"), call("call-2")),
                List.of(toolResult("call-2"), toolResult("call-1")));

        assertEquals(List.of("call-1", "call-2"),
                result.matched().stream().map(item -> item.call().id()).toList());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void shouldReportOnlyMissingCallsForPartialResults() {
        ToolResultReconciler.Reconciliation result = ToolResultReconciler.reconcile(
                List.of(call("call-1"), call("call-2")), List.of(toolResult("call-1")));

        assertEquals(1, result.matched().size());
        assertEquals(List.of("call-2"), result.missing().stream().map(ToolCallDTO::id).toList());
    }

    private ToolCallDTO call(String id) {
        return new ToolCallDTO(id, "executeCommand", "{\"command\":\"pwd\"}");
    }

    private ToolResultDTO toolResult(String id) {
        return new ToolResultDTO(id, "executeCommand", "/root", "pwd", "success");
    }
}
