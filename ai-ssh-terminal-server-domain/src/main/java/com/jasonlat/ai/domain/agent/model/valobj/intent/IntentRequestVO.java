package com.jasonlat.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * @author jasonlat
 * 2026-09-19  19:53
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentRequestVO {
    private String userId;
    private String userMessage;
    private String chatSessionId;

    private OpenAiApi llmIntentOpenAiApi;
    private String llmIntentModelName;
}
