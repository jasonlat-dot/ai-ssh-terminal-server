package com.jasonlat.ai.domain.agent.service.amory.matter.patch;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.springai.MessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * SpringAI 补丁
 * @author jasonlat
 * 2026-04-03  22:11
 */
public class LocalSpringAI extends BaseLlm {

    private static final Logger log = LoggerFactory.getLogger(LocalSpringAI.class);
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    public LocalSpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public LocalSpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public LocalSpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public LocalSpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public LocalSpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public LocalSpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public LocalSpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public LocalSpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new LocalMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        try {
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            log.debug("SpringAI chat Prompt: {}", prompt.toString());
            observabilityHandler.logRequest(prompt.toString(), model());

            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

            // Extract token counts if available
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);

            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
            return Flowable.just(llmResponse);
        } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        }
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");
        AtomicInteger totalTokens = new AtomicInteger();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();

        return Flowable.create(
                emitter -> {
                    try {
                        Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
                        log.debug("SpringAI streaming Prompt: {}", prompt.toString());
                        observabilityHandler.logRequest(prompt.toString(), model());

                        Flux<ChatResponse> responseFlux = streamingChatModel.stream(prompt);

                        responseFlux
                                .doOnError(
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        })
                                .subscribe(
                                        chatResponse -> {
                                            try {
                                                updateStreamingUsage(
                                                        chatResponse, totalTokens, inputTokens, outputTokens);
                                                if (isUsageOnlyStreamingChunk(chatResponse)) {
                                                    return;
                                                }
                                                // Use enhanced streaming-aware conversion
                                                LlmResponse llmResponse =
                                                        messageConverter.toLlmResponse(chatResponse, true);
                                                emitter.onNext(llmResponse);
                                            } catch (Exception e) {
                                                observabilityHandler.recordError(context, e);
                                                SpringAIErrorMapper.MappedError mappedError =
                                                        SpringAIErrorMapper.mapError(e);
                                                emitter.onError(
                                                        new RuntimeException(mappedError.getNormalizedMessage(), e));
                                            }
                                        },
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        },
                                        () -> {
                                            // 流式 token 来自最后的 usage 分片，不能再固定记录为 0。
                                            observabilityHandler.recordSuccess(
                                                    context,
                                                    totalTokens.get(),
                                                    inputTokens.get(),
                                                    outputTokens.get());
                                            emitter.onComplete();
                                        });
                    } catch (Exception e) {
                        observabilityHandler.recordError(context, e);
                        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
                        emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), e));
                    }
                },
                BackpressureStrategy.BUFFER);
    }
    private boolean isUsageOnlyStreamingChunk(ChatResponse chatResponse) {
        return chatResponse != null
                && (chatResponse.getResults() == null || chatResponse.getResults().isEmpty())
                && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null;
    }


    private void updateStreamingUsage(
            ChatResponse chatResponse,
            AtomicInteger totalTokens,
            AtomicInteger inputTokens,
            AtomicInteger outputTokens) {
        int currentTotal = extractTokenCount(chatResponse);
        int currentInput = extractInputTokenCount(chatResponse);
        int currentOutput = extractOutputTokenCount(chatResponse);
        // 只接受有意义的最终统计，避免后续的 0/0/0 分片覆盖真实值。
        if (currentTotal > 0 || currentInput > 0 || currentOutput > 0) {
            totalTokens.set(currentTotal > 0 ? currentTotal : currentInput + currentOutput);
            inputTokens.set(currentInput);
            outputTokens.set(currentOutput);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

}
