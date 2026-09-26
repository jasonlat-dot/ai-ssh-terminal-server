package com.jasonlat.ai.test.domain.agent;

import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.model.valobj.ChatAttachmentPolicy;
import com.jasonlat.ai.domain.agent.service.multimodal.*;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.domain.file.model.valobj.ObjectLocation;
import com.jasonlat.ai.domain.file.service.IFileService;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证跨格式转换、拒绝策略和附件内存准入，不连接真实存储或模型。 */
class ChatAttachmentServiceTest {
    private final IFileService files = mock(IFileService.class);
    private final ChatAttachmentService service = new ChatAttachmentService(files,
            new ChatAttachmentPolicy(4, 1024, 30, 1, false),
            List.of(new ImageAttachmentConverter(), new PdfAttachmentConverter(), new TextAttachmentConverter()));

    @Test
    void textAndPdfBecomeDifferentPartTypes() {
        addFile("text", "notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        addFile("pdf", "report.pdf", "%PDF-1.7\n".getBytes(StandardCharsets.UTF_8));
        var prepared = service.prepare(List.of("text", "pdf"), "alice", Set.of("application/pdf"));
        assertEquals("hello", prepared.parts().get(1).text().orElseThrow());
        assertEquals("application/pdf", prepared.parts().get(3).inlineData().orElseThrow().mimeType().orElseThrow());
        assertTrue(prepared.summary().contains("notes.txt"));
        assertFalse(prepared.summary().contains("hello"));
        verify(files).requireUploadedFile("text", "alice", false);
    }

    @Test
    void unsupportedModelRejectsDetectedMediaType() {
        addFile("image", "photo.png", new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        assertCode("CHAT_MODEL_MEDIA_UNSUPPORTED", () -> service.prepare(List.of("image"), "alice", Set.of()));
        verify(files).readContent(any(), eq(1024L));
    }

    @Test
    void mislabeledImageUsesActualMimeAndCannotBypassModelCapabilities() {
        // JPEG 内容误命名为 PNG：按真实类型发送，并使用同一类型校验模型能力。
        addFile("image", "photo.png", new byte[]{(byte) 255, (byte) 216, (byte) 255});
        var prepared = service.prepare(List.of("image"), "alice", Set.of("image/jpeg"));
        assertEquals("image/jpeg", prepared.parts().get(1).inlineData().orElseThrow().mimeType().orElseThrow());
        assertCode("CHAT_MODEL_MEDIA_UNSUPPORTED",
                () -> service.prepare(List.of("image"), "alice", Set.of("image/png")));
    }

    @Test
    void totalSizeAndDuplicateIdsAreRejectedBeforeRead() {
        when(files.requireUploadedFile("a", "alice", false)).thenReturn(metadata("a", "a.txt", 700));
        when(files.requireUploadedFile("b", "alice", false)).thenReturn(metadata("b", "b.txt", 700));
        assertCode("CHAT_ATTACHMENT_LIMIT", () -> service.prepare(List.of("a", "b"), "alice", Set.of()));
        assertCode("CHAT_ATTACHMENT_INVALID", () -> service.prepare(List.of("a", "a"), "alice", Set.of()));
        verify(files, never()).readContent(any(), anyLong());
    }

    @Test
    void officeFileIsNotMisrepresentedAsImage() {
        when(files.requireUploadedFile("office", "alice", false)).thenReturn(metadata("office", "report.docx", 10));
        assertCode("CHAT_ATTACHMENT_UNSUPPORTED", () -> service.prepare(List.of("office"), "alice", Set.of()));
        verify(files, never()).readContent(any(), anyLong());
    }

    @Test
    void invalidImageAndNonUtf8TextAreRejected() {
        assertCode("CHAT_ATTACHMENT_IMAGE_INVALID",
                () -> new ImageAttachmentConverter().convert("png", "not png".getBytes(StandardCharsets.UTF_8), 30));
        assertCode("CHAT_ATTACHMENT_TEXT_ENCODING_INVALID",
                () -> new TextAttachmentConverter().convert("txt", new byte[]{(byte) 0xff}, 30));
        Part image = new ImageAttachmentConverter().convert("png",
                new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 30);
        assertEquals("image/png", image.inlineData().orElseThrow().mimeType().orElseThrow());
    }

    @Test
    void invalidPdfAndBinaryTextHaveDistinctErrors() {
        assertCode("CHAT_ATTACHMENT_PDF_INVALID",
                () -> new PdfAttachmentConverter().convert("pdf", new byte[]{1, 2, 3}, 30));
        assertCode("CHAT_ATTACHMENT_TEXT_CONTENT_INVALID",
                () -> new TextAttachmentConverter().convert("txt", new byte[]{0}, 30));
    }

    @Test
    void textLimitAppliesToAllFilesTogether() {
        byte[] text = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
        addFile("a", "a.txt", text);
        addFile("b", "b.txt", text);
        assertCode("CHAT_ATTACHMENT_LIMIT", () -> service.prepare(List.of("a", "b"), "alice", Set.of()));
    }

    @Test
    void permitIsReleasedOnFailureAndCannotBeReleasedTwice() {
        ChatAttachmentService.Permit permit = service.acquire(true);
        assertCode("CHAT_ATTACHMENT_BUSY", () -> service.acquire(true));
        try (var ignored = service.acquire(false)) { /* 纯文本不占许可。 */ }
        permit.close();
        permit.close();
        try (var ignored = service.acquire(true)) {
            assertCode("CHAT_ATTACHMENT_BUSY", () -> service.acquire(true));
        }
    }

    private void addFile(String id, String name, byte[] bytes) {
        FileAssetEntity asset = metadata(id, name, bytes.length);
        when(files.requireUploadedFile(id, "alice", false)).thenReturn(asset);
        when(files.readContent(asset, 1024)).thenReturn(bytes);
    }

    private FileAssetEntity metadata(String id, String name, long size) {
        return FileAssetEntity.builder().fileId(id).originalName(name).size(size)
                .location(new ObjectLocation("minio-main", "files", "uploads/" + id, null)).build();
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(AppException.class, action).getCode());
    }
}
