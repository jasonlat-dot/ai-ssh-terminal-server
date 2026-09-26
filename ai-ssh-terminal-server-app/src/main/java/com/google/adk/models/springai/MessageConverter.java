/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.models.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;

/**
 * Compatibility patch for google-adk-spring-ai 1.2.0.
 *
 * <p>The upstream 1.2.0 converter drops ADK {@link FunctionResponse} parts. Consequently, the
 * follow-up OpenAI request contains an assistant tool call but no matching tool response and is
 * rejected with "No tool output found for function call". This copy preserves the upstream 1.2.0
 * behavior while adding FunctionResponse-to-ToolResponseMessage conversion.
 *
 * <p>Remove this class after upgrading to an ADK version that contains the upstream fix.
 */
public class MessageConverter {

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final ToolConverter toolConverter;
  private final ConfigMapper configMapper;

  public MessageConverter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.toolConverter = new ToolConverter();
    this.configMapper = new ConfigMapper();
  }

  /** Converts an ADK request into the Spring AI prompt consumed by the wrapped ChatModel. */
  public Prompt toLlmPrompt(LlmRequest llmRequest) {
    List<Message> messages = new ArrayList<>();

      List<String> allSystemMessages = new ArrayList<>(llmRequest.getSystemInstructions());

    List<Message> nonSystemMessages = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      String role = content.role().orElse("user").toLowerCase();
      if ("system".equals(role)) {
        StringBuilder systemText = new StringBuilder();
        for (Part part : content.parts().orElse(List.of())) {
          if (part.text().isPresent()) {
            systemText.append(part.text().get());
          }
        }
        if (!systemText.isEmpty()) {
          allSystemMessages.add(systemText.toString());
        }
      } else {
        nonSystemMessages.addAll(toSpringAiMessages(content));
      }
    }

    if (!allSystemMessages.isEmpty()) {
      messages.add(new SystemMessage(String.join("\n\n", allSystemMessages)));
    }

    messages.addAll(nonSystemMessages);

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(llmRequest.config());

    if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
      List<ToolCallback> toolCallbacks = toolConverter.convertToSpringAiTools(llmRequest.tools());
      if (!toolCallbacks.isEmpty()) {
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder();
        optionsBuilder.toolCallbacks(toolCallbacks);

        if (chatOptions != null) {
          if (chatOptions.getTemperature() != null) {
            optionsBuilder.temperature(chatOptions.getTemperature());
          }
          if (chatOptions.getMaxTokens() != null) {
            optionsBuilder.maxTokens(chatOptions.getMaxTokens());
          }
          if (chatOptions.getTopP() != null) {
            optionsBuilder.topP(chatOptions.getTopP());
          }
          if (chatOptions.getTopK() != null) {
            optionsBuilder.topK(chatOptions.getTopK());
          }
          if (chatOptions.getStopSequences() != null) {
            optionsBuilder.stopSequences(chatOptions.getStopSequences());
          }
          if (chatOptions.getModel() != null) {
            optionsBuilder.model(chatOptions.getModel());
          }
          if (chatOptions.getFrequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(chatOptions.getFrequencyPenalty());
          }
          if (chatOptions.getPresencePenalty() != null) {
            optionsBuilder.presencePenalty(chatOptions.getPresencePenalty());
          }
        }

        chatOptions = optionsBuilder.build();
      }
    }

    return new Prompt(messages, chatOptions);
  }

  /** Gets tool metadata from ADK tools for the Spring AI bridge's internal tracking. */
  public Map<String, ToolConverter.ToolMetadata> getToolRegistry(LlmRequest llmRequest) {
    return toolConverter.createToolRegistry(llmRequest.tools());
  }

  private List<Message> toSpringAiMessages(Content content) {
    String role = content.role().orElse("user").toLowerCase();

    return switch (role) {
      case "user" -> handleUserContent(content);
      case "model", "assistant" -> List.of(handleAssistantContent(content));
      case "system" -> List.of(handleSystemContent(content));
      default -> throw new IllegalStateException("Unexpected role: " + role);
    };
  }

  private List<Message> handleUserContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
    List<Media> mediaList = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        String functionCallId =
            functionResponse
                .id()
                .filter(id -> !id.isBlank())
                .orElseThrow(
                    () -> new IllegalStateException("Function response ID is missing"));

        toolResponses.add(
            new ToolResponseMessage.ToolResponse(
                functionCallId,
                functionResponse.name().orElse(""),
                toJson(functionResponse.response().orElse(Map.of()))));
      } else if (part.inlineData().isPresent()) {
        com.google.genai.types.Blob blob = part.inlineData().get();
        if (blob.mimeType().isPresent() && blob.data().isPresent()) {
          try {
            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
            org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(blob.data().get());
            // Spring AI 1.1.5 按 application/pdf 构造 input file；提供带扩展名的中性文件名。
            mediaList.add("application/pdf".equals(mimeType.toString())
                ? Media.builder().mimeType(mimeType).data(resource).name("attachment.pdf").build()
                : new Media(mimeType, resource));
          } catch (Exception e) {
            throw new IllegalArgumentException("无法转换内联媒体附件", e);
          }
        } else {
          throw new IllegalArgumentException("内联媒体缺少 MIME 或正文");
        }
      } else if (part.fileData().isPresent()) {
        com.google.genai.types.FileData fileData = part.fileData().get();
        if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
          try {
            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
            if ("application/pdf".equals(mimeType.toString())) {
              throw new IllegalArgumentException("PDF 必须先读取正文，再以内联数据发送");
            }
            URI uri = URI.create(fileData.fileUri().get());
            mediaList.add(new Media(mimeType, uri));
          } catch (Exception e) {
            throw new IllegalArgumentException("无法转换媒体附件引用", e);
          }
        } else {
          throw new IllegalArgumentException("媒体附件缺少 MIME 或 URI");
        }
      }
    }

    List<Message> messages = new ArrayList<>();
    String text = textBuilder.toString();

    // A function-response-only turn must become a ToolResponseMessage directly. Adding an empty
    // UserMessage between the assistant tool call and its response breaks OpenAI's message order.
    if (!text.isEmpty() || !mediaList.isEmpty() || toolResponses.isEmpty()) {
      messages.add(UserMessage.builder().text(text).media(mediaList).build());
    }
    if (!toolResponses.isEmpty()) {
      messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    return messages;
  }

  private AssistantMessage handleAssistantContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        toolCalls.add(
            new AssistantMessage.ToolCall(
                functionCall
                    .id()
                    .orElseThrow(() -> new IllegalStateException("Function call ID is missing")),
                "function",
                functionCall
                    .name()
                    .orElseThrow(() -> new IllegalStateException("Function call name is missing")),
                toJson(functionCall.args().orElse(Map.of()))));
      }
    }

    String text = textBuilder.toString();
    if (toolCalls.isEmpty()) {
      return new AssistantMessage(text);
    }
    return AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
  }

  private SystemMessage handleSystemContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      }
    }
    return new SystemMessage(textBuilder.toString());
  }

  /** Converts a Spring AI response to an ADK response. */
  public LlmResponse toLlmResponse(ChatResponse chatResponse) {
    return toLlmResponse(chatResponse, false);
  }

  /** Converts a Spring AI response to an ADK response with streaming context. */
  public LlmResponse toLlmResponse(ChatResponse chatResponse, boolean isStreaming) {
    if (chatResponse == null) {
      return LlmResponse.builder().build();
    }

    Optional<GenerateContentResponseUsageMetadata> usageMetadata = toUsageMetadata(chatResponse);

    /*
     * OpenAI 流式 usage 可能作为最后一个无 choices 的独立分片返回。即使没有
     * Generation，也必须把 token 信息转换为 LlmResponse，否则插件永远读不到。
     */
    if (CollectionUtils.isEmpty(chatResponse.getResults())) {
      LlmResponse.Builder responseBuilder = LlmResponse.builder();
      usageMetadata.ifPresent(responseBuilder::usageMetadata);
      /*
       * OpenAI 在 include_usage=true 时会在 choices 结束后额外发送一个只有 usage 的分片。
       * 该分片只是计费元数据，不是新的模型内容，也不代表整个 ADK invocation 已完成。
       *
       * 尤其在工具调用场景中，usage 分片可能紧跟 FunctionCall 到达。若在这里设置
       * turnComplete=true，ADK 会在工具执行完成后直接结束 invocation，不再把
       * FunctionResponse 交给模型生成最终总结。因此这里只透传 usageMetadata，
       * turnComplete 必须留空，由真正带 finishReason 的 Generation 决定回合边界。
       */
      return responseBuilder.build();
    }

    Generation generation = chatResponse.getResult();
    AssistantMessage assistantMessage = generation.getOutput();
    Content content = convertAssistantMessageToContent(assistantMessage);

    boolean isPartial = isStreaming && isPartialResponse(assistantMessage);
    boolean isTurnComplete = !isStreaming || isTurnCompleteResponse(chatResponse);

    LlmResponse.Builder responseBuilder = LlmResponse.builder()
        .content(content)
        .partial(isPartial)
        .turnComplete(isTurnComplete);
    usageMetadata.ifPresent(responseBuilder::usageMetadata);
    return responseBuilder.build();
  }

  /** Maps Spring AI token usage to the metadata type consumed by Google ADK callbacks. */
  private Optional<GenerateContentResponseUsageMetadata> toUsageMetadata(
      ChatResponse chatResponse) {
    if (chatResponse.getMetadata() == null || chatResponse.getMetadata().getUsage() == null) {
      return Optional.empty();
    }

    Usage usage = chatResponse.getMetadata().getUsage();
    Integer promptTokens = usage.getPromptTokens();
    Integer completionTokens = usage.getCompletionTokens();
    Integer totalTokens = usage.getTotalTokens();
    if (!hasPositiveValue(promptTokens)
        && !hasPositiveValue(completionTokens)
        && !hasPositiveValue(totalTokens)) {
      return Optional.empty();
    }

    GenerateContentResponseUsageMetadata.Builder builder =
        GenerateContentResponseUsageMetadata.builder();
    if (promptTokens != null) {
      builder.promptTokenCount(promptTokens);
    }
    if (completionTokens != null) {
      builder.candidatesTokenCount(completionTokens);
    }
    if (totalTokens != null) {
      builder.totalTokenCount(totalTokens);
    }
    return Optional.of(builder.build());
  }

  private boolean hasPositiveValue(Integer value) {
    return value != null && value > 0;
  }

  private boolean isPartialResponse(AssistantMessage message) {
    if (message.getText() != null && !message.getText().isEmpty()) {
      String text = message.getText().trim();
      if (!text.endsWith(".")
          && !text.endsWith("!")
          && !text.endsWith("?")
          && !text.endsWith("\n")
          && message.getToolCalls().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private boolean isTurnCompleteResponse(ChatResponse response) {
    Generation generation = response.getResult();
    if (generation != null && generation.getMetadata() != null) {
      String finishReason = generation.getMetadata().getFinishReason();
      return finishReason == null
          || "stop".equals(finishReason)
          || "tool_calls".equals(finishReason);
    }
    return true;
  }

  private Content convertAssistantMessageToContent(AssistantMessage assistantMessage) {
    List<Part> parts = new ArrayList<>();

    if (assistantMessage.getText() != null && !assistantMessage.getText().isEmpty()) {
      parts.add(Part.fromText(assistantMessage.getText()));
    }

    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
      if ("function".equals(toolCall.type())) {
        try {
          Map<String, Object> args =
              objectMapper.readValue(toolCall.arguments(), MAP_TYPE_REFERENCE);
          FunctionCall functionCall =
              FunctionCall.builder()
                  .id(toolCall.id())
                  .name(toolCall.name())
                  .args(args)
                  .build();
          parts.add(Part.builder().functionCall(functionCall).build());
        } catch (JsonProcessingException e) {
          throw MessageConversionException.jsonParsingFailed("tool call arguments", e);
        }
      }
    }

    return Content.builder().role("model").parts(parts).build();
  }

  private String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw MessageConversionException.jsonParsingFailed("object serialization", e);
    }
  }
}
