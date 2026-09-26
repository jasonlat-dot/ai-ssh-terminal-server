package com.jasonlat.ai.domain.agent.service.multimodal;

import com.google.genai.types.Part;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** PDF 以原始字节交给支持 PDF 的模型，避免把下载 URL 当作 file_data。 */
@Component
public class PdfAttachmentConverter implements ChatAttachmentConverter {
    @Override public boolean supports(String extension) { return "pdf".equals(extension); }
    @Override public String mediaType(String extension) { return "application/pdf"; }

    @Override
    public Part convert(String extension, byte[] bytes, int maxTextChars) {
        String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        if (!header.contains("%PDF-")) throw new AppException(ResponseCode.CHAT_ATTACHMENT_CONTENT_INVALID);
        return Part.fromBytes(bytes, mediaType(extension));
    }
}
