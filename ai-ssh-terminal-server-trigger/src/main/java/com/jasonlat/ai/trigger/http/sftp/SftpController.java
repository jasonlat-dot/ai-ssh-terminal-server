package com.jasonlat.ai.trigger.http.sftp;

import com.jasonlat.ai.cases.sftp.SftpServiceCase;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.trigger.api.dto.sftp.SftpRequests;
import com.jasonlat.ai.trigger.api.response.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

/** Tauri 文件管理入口；二进制端点直接转发流，不经过聊天附件/MinIO。 */
@RestController
@RequestMapping("/api/v1/sftp")
public class SftpController {
    private final SftpServiceCase service;
    private final SftpEventStream events;
    public SftpController(SftpServiceCase service, SftpEventStream events) { this.service = service; this.events = events; }
    private String owner(Principal principal) { return service.owner(principal == null ? null : principal.getName()); }

    @PostMapping("/sessions")
    public Response<SessionView> open(@RequestBody SftpRequests.OpenSession request, Principal principal) {
        return Response.success("SFTP 会话已打开", service.open(owner(principal), request));
    }
    @GetMapping("/sessions/{id}")
    public Response<SessionView> session(@PathVariable String id, Principal principal) { return Response.success("查询成功", service.session(owner(principal), id)); }
    @DeleteMapping("/sessions/{id}")
    public Response<Void> close(@PathVariable String id, Principal principal) { service.close(owner(principal), id); return Response.success("已关闭", null); }
    @GetMapping("/sessions/{id}/entries")
    public Response<List<Entry>> entries(@PathVariable String id, @RequestParam(required = false) String path, Principal principal) {
        return Response.success("查询成功", service.list(owner(principal), id, path));
    }
    @PostMapping("/sessions/{id}/directories")
    public Response<Void> mkdir(@PathVariable String id, @RequestBody SftpRequests.CreateDirectory request, Principal principal) {
        service.mkdir(owner(principal), id, request); return Response.success("目录已创建", null);
    }
    @GetMapping("/sessions/{id}/transfers")
    public Response<List<TransferView>> tasks(@PathVariable String id, Principal principal) { return Response.success("查询成功", service.tasks(owner(principal), id)); }
    @GetMapping(value = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, Principal principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return events.open(owner(principal), id);
    }
    @PostMapping("/transfers")
    public Response<TransferView> create(@RequestBody SftpRequests.CreateTransfer request, Principal principal) {
        return Response.success("任务已创建", service.create(owner(principal), request));
    }
    @GetMapping("/transfers/{id}")
    public Response<TransferView> task(@PathVariable String id, Principal principal) { return Response.success("查询成功", service.task(owner(principal), id)); }
    @PostMapping("/transfers/{id}/cancel")
    public Response<Void> cancel(@PathVariable String id, Principal principal) { service.cancel(owner(principal), id); return Response.success("取消已请求", null); }
    @PutMapping(value = "/transfers/{id}/items/{itemId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Response<Void> upload(@PathVariable String id, @PathVariable String itemId,
                                 HttpServletRequest request, Principal principal) throws IOException {
        // 处理完传输才返回，不能把 Servlet 输入流交给后台后提前结束请求。
        service.upload(owner(principal), id, itemId, request.getInputStream(), request.getContentLengthLong());
        return Response.success("上传完成", null);
    }
    @GetMapping("/transfers/{id}/items/{itemId}/content")
    public void download(@PathVariable String id, @PathVariable String itemId, Principal principal,
                         HttpServletResponse response) throws IOException {
        String owner = owner(principal);
        ItemView item = service.downloadItem(owner, id, itemId);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String name = item.relativePath().substring(item.relativePath().lastIndexOf('/') + 1);
        response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        // 不提前设置 Content-Length：远端校验失败时，仍可返回标准 JSON 错误。
        // Tauri 使用任务清单大小校验下载流，并在保存成功后发送确认。
        service.download(owner, id, itemId, response.getOutputStream());
    }
    @PostMapping("/transfers/{id}/items/{itemId}/confirm")
    public Response<Void> confirm(@PathVariable String id, @PathVariable String itemId,
                                  @RequestBody SftpRequests.ConfirmDownload request, Principal principal) {
        service.confirm(owner(principal), id, itemId, request); return Response.success("确认成功", null);
    }
}
