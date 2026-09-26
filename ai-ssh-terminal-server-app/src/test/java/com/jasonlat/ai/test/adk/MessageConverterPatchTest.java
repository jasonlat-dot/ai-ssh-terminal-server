package com.jasonlat.ai.test.adk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
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
import org.springframework.ai.chat.messages.UserMessage;
import com.jasonlat.ai.domain.agent.service.amory.matter.patch.LocalMessageConverter;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class MessageConverterPatchTest {

  @Test
  void mediaStaysOnItsOwnTurnWithoutDuplicationAndPdfHasFilename() {
    Content first = Content.builder().role("user")
        .parts(List.of(Part.fromText("image"), Part.fromBytes(new byte[]{1}, "image/png"))).build();
    Content reply = Content.builder().role("model").parts(List.of(Part.fromText("seen"))).build();
    Content second = Content.builder().role("user")
        .parts(List.of(Part.fromText("pdf"), Part.fromBytes(new byte[]{2}, "application/pdf"))).build();
    Prompt prompt = new LocalMessageConverter(new ObjectMapper()).toLlmPrompt(LlmRequest.builder()
        .model("test-model").contents(List.of(first, reply, second)).tools(Map.of()).build());
    UserMessage firstMessage = assertInstanceOf(UserMessage.class, prompt.getInstructions().get(0));
    UserMessage secondMessage = assertInstanceOf(UserMessage.class, prompt.getInstructions().get(2));
    assertEquals(1, firstMessage.getMedia().size());
    assertEquals("image/png", firstMessage.getMedia().getFirst().getMimeType().toString());
    assertEquals(1, secondMessage.getMedia().size());
    assertEquals("application/pdf", secondMessage.getMedia().getFirst().getMimeType().toString());
    assertTrue(secondMessage.getMedia().getFirst().getName().endsWith(".pdf"));
  }

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

  @Test
  void shouldMapSpringAiUsageToAdkUsageMetadata() {
    ChatResponse response =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("done"))),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(12, 7, 19))
                .build());

    LlmResponse converted =
        new MessageConverter(new ObjectMapper()).toLlmResponse(response, true);

    assertTrue(converted.usageMetadata().isPresent());
    assertEquals(12, converted.usageMetadata().orElseThrow().promptTokenCount().orElseThrow());
    assertEquals(7, converted.usageMetadata().orElseThrow().candidatesTokenCount().orElseThrow());
    assertEquals(19, converted.usageMetadata().orElseThrow().totalTokenCount().orElseThrow());
  }

  @Test
  void shouldKeepUsageOnlyStreamingChunk() {
    ChatResponse response =
        new ChatResponse(
            List.of(),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(20, 9, 29))
                .build());

    LlmResponse converted =
        new MessageConverter(new ObjectMapper()).toLlmResponse(response, true);

    assertTrue(converted.content().isEmpty());
    assertTrue(converted.turnComplete().isEmpty());
    assertTrue(converted.partial().isEmpty());
    assertEquals(20, converted.usageMetadata().orElseThrow().promptTokenCount().orElseThrow());
    assertEquals(9, converted.usageMetadata().orElseThrow().candidatesTokenCount().orElseThrow());
    assertEquals(29, converted.usageMetadata().orElseThrow().totalTokenCount().orElseThrow());
  }

  @Test
  void shouldNotFinishAdkInvocationFromUsageOnlyChunkAfterToolCall() {
    ChatResponse usageOnlyResponse =
        new ChatResponse(
            List.of(),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(1123, 49, 1172))
                .build());

    LlmResponse converted =
        new MessageConverter(new ObjectMapper()).toLlmResponse(usageOnlyResponse, true);

    assertTrue(converted.content().isEmpty());
    assertTrue(converted.usageMetadata().isPresent());
    assertTrue(converted.turnComplete().isEmpty(),
        "usage-only chunk must not terminate ADK before the tool-result summary call");
  }

  @Test
  void shouldIgnoreZeroUsageOnIntermediateStreamingChunk() {
    ChatResponse response =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("partial"))),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(0, 0, 0))
                .build());

    LlmResponse converted =
        new MessageConverter(new ObjectMapper()).toLlmResponse(response, true);

    assertTrue(converted.usageMetadata().isEmpty());
  }
}
