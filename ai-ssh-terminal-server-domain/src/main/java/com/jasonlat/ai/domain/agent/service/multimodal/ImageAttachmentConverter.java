package com.jasonlat.ai.domain.agent.service.multimodal;

import com.google.genai.types.Part;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 按文件头识别受支持的图片格式；后缀只用于选择策略，发送 MIME 以实际内容为准。 */
@Component
public class ImageAttachmentConverter implements ChatAttachmentConverter {
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp");

    @Override public boolean supports(String extension) { return TYPES.containsKey(extension); }
    @Override public String mediaType(String extension) { return TYPES.get(extension); }

    @Override
    public Part convert(String extension, byte[] bytes, int maxTextChars) {
        // 浏览器复制或另存的图片可能使用错误后缀；只接受这里能识别的三种实际格式。
        String detectedType;
        if (startsWith(bytes, 0, 137, 80, 78, 71, 13, 10, 26, 10)) {
            detectedType = "image/png";
        } else if (startsWith(bytes, 0, 255, 216, 255)) {
            detectedType = "image/jpeg";
        } else if (startsWith(bytes, 0, 82, 73, 70, 70) && startsWith(bytes, 8, 87, 69, 66, 80)) {
            detectedType = "image/webp";
        } else {
            throw new AppException(ResponseCode.CHAT_ATTACHMENT_IMAGE_INVALID);
        }
        return Part.fromBytes(bytes, detectedType);
    }

    private boolean startsWith(byte[] bytes, int offset, int... prefix) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[offset + i] & 255) != prefix[i]) return false;
        }
        return true;
    }
}
