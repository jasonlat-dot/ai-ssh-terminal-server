package com.jasonlat.ai.domain.agent.service.multimodal;

import com.google.genai.types.Part;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 文本文件读取正文后参与对话，不能走 Spring AI 的图片媒体分支。 */
@Component
public class TextAttachmentConverter implements ChatAttachmentConverter {
    private static final Set<String> EXTENSIONS = Set.of("txt", "log", "csv", "json", "yaml", "yml", "md");
    @Override public boolean supports(String extension) { return EXTENSIONS.contains(extension); }
    @Override public String mediaType(String extension) { return null; }

    @Override
    public Part convert(String extension, byte[] bytes, int maxTextChars) {
        try {
            // 严格 UTF-8 解码，拒绝二进制和乱码，不让替换字符掩盖格式错误。
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw new AppException(ResponseCode.CHAT_ATTACHMENT_TEXT_CONTENT_INVALID);
            }
            if (text.length() > maxTextChars) throw new AppException(ResponseCode.CHAT_ATTACHMENT_LIMIT);
            return Part.fromText(text);
        } catch (CharacterCodingException e) {
            throw new AppException(ResponseCode.CHAT_ATTACHMENT_TEXT_ENCODING_INVALID);
        }
    }
}
