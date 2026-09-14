package com.jasonlat.ai.test.adk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

class MessageConverterPatchTest {

  @Test
  void shouldPreserveFunctionCallIdInToolResponse() {
    String callId = "fc_test_123";

    FunctionCall functionCall =
        FunctionCall.builder()
            .id(callId)
            .name("executeCommand")
            .args(Map.of("command", "docker --version"))
            .build();

    Content assistantContent =
        Content.builder()
            .role("model")
            .parts(List.of(Part.builder().functionCall(functionCall).build()))
            .build();

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .id(callId)
            .name("executeCommand")
            .response(Map.of("output", "Docker version 27.0.0"))
            .build();

    Content toolResultContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.builder().functionResponse(functionResponse).build()))
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .model("test-model")
            .contents(List.of(assistantContent, toolResultContent))
            .tools(Map.of())
            .build();

    MessageConverter converter = new MessageConverter(new ObjectMapper());
    Prompt prompt = converter.toLlmPrompt(request);
    List<Message> messages = prompt.getInstructions();

    assertEquals(2, messages.size());

    AssistantMessage assistantMessage =
        assertInstanceOf(AssistantMessage.class, messages.get(0));
    ToolResponseMessage toolResponseMessage =
        assertInstanceOf(ToolResponseMessage.class, messages.get(1));

    assertEquals(callId, assistantMessage.getToolCalls().get(0).id());
    assertEquals(callId, toolResponseMessage.getResponses().get(0).id());
    assertEquals("executeCommand", toolResponseMessage.getResponses().get(0).name());
    assertEquals(
        "{\"output\":\"Docker version 27.0.0\"}",
        toolResponseMessage.getResponses().get(0).responseData());
  }
}
