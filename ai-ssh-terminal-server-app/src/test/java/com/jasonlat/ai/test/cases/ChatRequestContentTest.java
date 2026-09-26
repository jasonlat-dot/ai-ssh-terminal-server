package com.jasonlat.ai.test.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jasonlat.ai.cases.react.multimodal.ChatRequestContentSupport;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatRequestContentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsAttachmentOnlyAndIgnoresForgedAuthenticatedIdentity() throws Exception {
        ChatRequest request = mapper.readValue("""
                {"attachments":[{"fileId":"file-1"}],"authenticatedUserId":"victim"}
                """, ChatRequest.class);
        ChatRequestContentSupport.validateAndNormalize(request);
        assertFalse(request.getMessage().isBlank());
        assertEquals("file-1", ChatRequestContentSupport.fileIds(request).getFirst());
        assertNull(request.getAuthenticatedUserId());
    }

    @Test
    void preservesTextAndRejectsEmptyRequestOrMalformedAttachment() throws Exception {
        ChatRequest text = mapper.readValue("{\"message\":\"hello\"}", ChatRequest.class);
        ChatRequestContentSupport.validateAndNormalize(text);
        assertEquals("hello", text.getMessage());
        assertEquals("CHAT_CONTENT_REQUIRED", assertThrows(AppException.class,
                () -> ChatRequestContentSupport.validateAndNormalize(new ChatRequest())).getCode());
        ChatRequest invalid = mapper.readValue("{\"message\":\"hello\",\"attachments\":[null]}", ChatRequest.class);
        assertEquals("CHAT_ATTACHMENT_INVALID", assertThrows(AppException.class,
                () -> ChatRequestContentSupport.validateAndNormalize(invalid)).getCode());
    }
}
