package com.jasonlat.ai.domain.agent.service.amory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import com.google.genai.types.Part;
import com.google.genai.types.Content;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * PTC补丁：修复谷歌SDK和SpringAI兼容时，识别不了图片（多模态）的问题[参数丢失了]
 * @author jasonlat
 * 2026-04-03  22:04
 */
public class LocalMessageConverter extends MessageConverter {

    public LocalMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {

        List<Media> mediaList = new ArrayList<>();
        for (Content content : llmRequest.contents()) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part.inlineData().isPresent()) {
                    // Handle inline media data (images, audio, video, etc.)
                    com.google.genai.types.Blob blob = part.inlineData().get();
                    if (blob.mimeType().isPresent() && blob.data().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
                            // Create Media object from inline data using ByteArrayResource
                            org.springframework.core.io.ByteArrayResource resource =
                                    new org.springframework.core.io.ByteArrayResource(blob.data().get());
                            mediaList.add(new Media(mimeType, resource));
                        } catch (Exception e) {
                            // Log warning but continue processing other parts
                            // In production, consider proper logging framework
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + blob.mimeType().get());
                        }
                    }
                } else if (part.fileData().isPresent()) {
                    // Handle file-based media (URI references)
                    com.google.genai.types.FileData fileData = part.fileData().get();
                    if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
                            // Create Media object from file URI
                            URI uri = URI.create(fileData.fileUri().get());
                            mediaList.add(new Media(mimeType, uri));
                        } catch (Exception e) {
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + fileData.mimeType().get());
                        }
                    }
                }
            }
        }

        Prompt llmPrompt = super.toLlmPrompt(llmRequest);
        llmPrompt.getUserMessage().getMedia().addAll(mediaList);

        return llmPrompt;
    }
}
