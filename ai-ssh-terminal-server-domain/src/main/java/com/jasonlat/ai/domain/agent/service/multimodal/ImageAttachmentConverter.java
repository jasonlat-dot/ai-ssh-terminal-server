package com.jasonlat.ai.domain.agent.service.multimodal;

import com.google.genai.types.Part;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 图片直接作为媒体传给模型；文件头检查避免将改后缀的任意文件当作图片。 */
@Component
public class ImageAttachmentConverter implements ChatAttachmentConverter {
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp");

    @Override public boolean supports(String extension) { return TYPES.containsKey(extension); }
    @Override public String mediaType(String extension) { return TYPES.get(extension); }

    @Override
    public Part convert(String extension, byte[] bytes, int maxTextChars) {
        boolean valid = switch (extension) {
            case "png" -> startsWith(bytes, 0, 137, 80, 78, 71, 13, 10, 26, 10);
            case "jpg", "jpeg" -> startsWith(bytes, 0, 255, 216, 255);
            case "webp" -> startsWith(bytes, 0, 82, 73, 70, 70) && startsWith(bytes, 8, 87, 69, 66, 80);
            default -> false;
        };
        if (!valid) throw new AppException(ResponseCode.CHAT_ATTACHMENT_CONTENT_INVALID);
        return Part.fromBytes(bytes, mediaType(extension));
    }

    private boolean startsWith(byte[] bytes, int offset, int... prefix) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[offset + i] & 255) != prefix[i]) return false;
        }
        return true;
    }
}
