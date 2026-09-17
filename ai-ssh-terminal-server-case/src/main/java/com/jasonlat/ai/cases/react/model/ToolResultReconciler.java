package com.jasonlat.ai.cases.react.model;

import com.jasonlat.ai.trigger.api.dto.ToolCallDTO;
import com.jasonlat.ai.trigger.api.dto.ToolResultDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 toolCallId 对齐 ADK 工具调用与结果，支持单工具、多工具和部分结果。 */
public final class ToolResultReconciler {

    private ToolResultReconciler() {
    }

    public static Reconciliation reconcile(List<ToolCallDTO> calls, List<ToolResultDTO> results) {
        Map<String, ToolResultDTO> resultsById = new LinkedHashMap<>();
        if (results != null) {
            for (ToolResultDTO result : results) {
                if (result != null && result.id() != null) {
                    resultsById.putIfAbsent(result.id(), result);
                }
            }
        }

        List<MatchedTool> matched = new ArrayList<>();
        List<ToolCallDTO> missing = new ArrayList<>();
        if (calls != null) {
            for (ToolCallDTO call : calls) {
                if (call == null) continue;
                ToolResultDTO result = resultsById.get(call.id());
                if (result == null) {
                    missing.add(call);
                } else {
                    matched.add(new MatchedTool(call, result));
                }
            }
        }
        return new Reconciliation(List.copyOf(matched), List.copyOf(missing));
    }

    public record MatchedTool(ToolCallDTO call, ToolResultDTO result) {
    }

    public record Reconciliation(List<MatchedTool> matched, List<ToolCallDTO> missing) {
    }
}
