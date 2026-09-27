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
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

/**
 * Tauri 文件管理 HTTP 入口。
 *
 * <p>目录与任务接口使用 JSON；上传和下载端点直接转发二进制流，不经过聊天附件或 MinIO，
 * 避免大文件在应用内存中形成完整副本。</p>
 */
@RestController
@RequestMapping("/api/v1/sftp")
@Slf4j
public class SftpController {
    private final SftpServiceCase service;
    private final SftpEventStream events;

    /** 注入应用门面和进度事件流；Controller 本身不直接持有 JSch 连接。 */
    public SftpController(SftpServiceCase service, SftpEventStream events) {
        this.service = service;
        this.events = events;
    }

    /**
     * 从 Spring Security 的 Principal 解析资源所有者。
     *
     * <p>客户端不能提交 userId。所有会话、任务、文件操作都使用这里得到的 owner 做归属校验，
     * 防止知道另一个会话 ID 的用户越权访问。</p>
     */
    private String owner(Principal principal) {
        // Principal 由服务端认证体系产生，不能使用请求体或查询参数中的用户标识代替。
        String authenticatedUserId = principal == null ? null : principal.getName();
        // 应用门面负责决定未登录部署是否允许使用固定 anonymousUserId。
        String owner = service.owner(authenticatedUserId);
        log.info(
                "SFTP HTTP 请求身份已解析 userId={} authenticatedPrincipal={}",
                owner,
                authenticatedUserId != null
        );
        return owner;
    }

    /**
     * 打开文件管理会话。
     *
     * <p>此接口使用已保存的 SSH 连接配置新建一条独立 SSH/SFTP 连接；它不复用终端 Channel，
     * 所以关闭终端页签不会中断文件传输。</p>
     */
    @PostMapping("/sessions")
    public Response<SessionView> open(@RequestBody SftpRequests.OpenSession request, Principal principal) {
        // 返回值中的 sftpSessionId 是后续目录、任务和关闭接口使用的会话标识。
        return Response.success("SFTP 会话已打开", service.open(owner(principal), request));
    }

    /** 查询文件管理会话状态，并刷新服务端的会话访问时间。 */
    @GetMapping("/sessions/{id}")
    public Response<SessionView> session(@PathVariable String id, Principal principal) {
        // 此处 id 表示 sftpSessionId，不是 SSH connectionId，也不是终端 terminalSessionId。
        return Response.success("查询成功", service.session(owner(principal), id));
    }
    /** 关闭文件管理会话以及它持有的底层 JSch SSH 连接。 */
    @DeleteMapping("/sessions/{id}")
    public Response<Void> close(@PathVariable String id, Principal principal) {
        // 先校验当前用户是否拥有该 sftpSessionId，再释放服务端资源。
        service.close(owner(principal), id);
        return Response.success("已关闭", null);
    }
    /**
     * 列出远程目录。
     *
     * @param path 要浏览的远程路径；为空时列出会话根目录
     */
    @GetMapping("/sessions/{id}/entries")
    public Response<List<Entry>> entries(@PathVariable String id, @RequestParam(required = false) String path, Principal principal) {
        // path 为空时由领域层使用会话 rootPath；所有显式路径都必须位于 rootPath 内。
        return Response.success("查询成功", service.list(owner(principal), id, path));
    }
    /** 创建远程目录；领域层会校验路径必须位于当前会话根目录内。 */
    @PostMapping("/sessions/{id}/directories")
    public Response<Void> mkdir(@PathVariable String id, @RequestBody SftpRequests.CreateDirectory request, Principal principal) {
        // Controller 只做协议适配，DTO 校验和安全路径解析分别由应用层、领域层负责。
        service.mkdir(owner(principal), id, request);
        return Response.success("目录已创建", null);
    }

    /** 创建远程空文件；目标已经存在时不会覆盖。 */
    @PostMapping("/sessions/{id}/files")
    public Response<Void> createFile(
            @PathVariable String id,
            @RequestBody SftpRequests.CreateFile request,
            Principal principal
    ) {
        // 创建的是 0 字节普通文件；不会把客户端内容传到该接口，也不会覆盖同名条目。
        service.createFile(owner(principal), id, request);
        return Response.success("文件已创建", null);
    }

    /**
     * 删除一个远程普通文件或空目录。
     *
     * <p>服务端会先使用 lstat 判断类型，再分别调用 JSch 的 rm 或 rmdir；不会递归删除，
     * 也禁止删除文件管理根目录。</p>
     */
    @DeleteMapping("/sessions/{id}/entries")
    public Response<Void> delete(
            @PathVariable String id,
            @RequestParam String path,
            Principal principal
    ) {
        // path 指向具体条目；领域层通过 lstat 决定调用 JSch rm 还是 rmdir。
        service.delete(owner(principal), id, path);
        return Response.success("条目已删除", null);
    }

    /** 返回指定文件管理会话下的传输任务列表，供页面恢复传输队列。 */
    @GetMapping("/sessions/{id}/transfers")
    public Response<List<TransferView>> tasks(@PathVariable String id, Principal principal) {
        // 此处 id 仍是 sftpSessionId，用于恢复当前文件管理窗口的传输队列。
        return Response.success("查询成功", service.tasks(owner(principal), id));
    }
    /**
     * 建立 SSE 进度通道。
     *
     * <p>文件字节仍通过普通 HTTP 上传/下载；SSE 只推送任务状态和已传输字节数。</p>
     */
    @GetMapping(value = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, Principal principal, HttpServletResponse response) {
        // 禁止代理缓存和缓冲，否则客户端可能长时间收不到实时进度事件。
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        // SSE 断开只影响进度展示，不会取消已经开始的上传或下载任务。
        return events.open(owner(principal), id);
    }
    /**
     * 创建传输任务和清单。
     *
     * <p>上传清单由 Tauri 扫描本地文件后提交；下载清单由服务端扫描远端目录生成。
     * 该接口只创建任务，不传输文件内容。</p>
     */
    @PostMapping("/transfers")
    public Response<TransferView> create(@RequestBody SftpRequests.CreateTransfer request, Principal principal) {
        // 创建后返回 transferId 和条目 itemId；实际文件内容通过下面的 content 接口逐项传输。
        return Response.success("任务已创建", service.create(owner(principal), request));
    }
    /** 查询单个传输任务的清单、进度和最终状态。 */
    @GetMapping("/transfers/{id}")
    public Response<TransferView> task(@PathVariable String id, Principal principal) {
        // 此处 id 开始表示 transferId，与 /sessions/{id} 中的 sftpSessionId 含义不同。
        return Response.success("查询成功", service.task(owner(principal), id));
    }
    /** 标记任务取消；JSch 进度监听器会在下一批字节回调时协作式停止。 */
    @PostMapping("/transfers/{id}/cancel")
    public Response<Void> cancel(@PathVariable String id, Principal principal) {
        // 接口只发出取消请求；正在执行的阻塞网络调用由领域层关闭 Channel 来唤醒。
        service.cancel(owner(principal), id);
        return Response.success("取消已请求", null);
    }
    /**
     * 上传清单中的单个文件。
     *
     * <p>请求体直接流入远程临时文件，不把整个文件读入 JVM 内存；长度校验成功后，
     * 领域层再根据冲突策略提交到最终路径。</p>
     */
    @PutMapping(value = "/transfers/{id}/items/{itemId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Response<Void> upload(
            @PathVariable String id,
            @PathVariable String itemId,
            HttpServletRequest request,
            Principal principal
    ) throws IOException {
        // 处理完传输才返回，不能把 Servlet 输入流交给后台后提前结束请求。
        // Content-Length 会与任务清单中的声明大小比较，阻止少传或多传数据。
        service.upload(owner(principal), id, itemId, request.getInputStream(), request.getContentLengthLong());
        return Response.success("上传完成", null);
    }
    /**
     * 下载清单中的单个远程文件。
     *
     * <p>JSch 的 get 输出流直接连接 Servlet 响应流，Tauri 落盘完成后还要调用 confirm，
     * 因此“后端读取完成”和“客户端保存成功”是两个独立状态。</p>
     */
    @GetMapping("/transfers/{id}/items/{itemId}/content")
    public void download(
            @PathVariable String id,
            @PathVariable String itemId,
            Principal principal,
            HttpServletResponse response
    ) throws IOException {
        // owner 只解析一次，保证元数据查询和后续文件读取使用同一个用户身份。
        String owner = owner(principal);
        // 在响应尚未提交时取得条目名称；若任务或条目无效，仍能返回标准 JSON 错误。
        ItemView item = service.downloadItem(owner, id, itemId);
        // 下载统一按二进制处理，不根据远端扩展名让浏览器猜测并执行内容。
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 只取相对路径最后一段作为文件名，不能把服务端目录结构写入响应头。
        String name = item.relativePath().substring(item.relativePath().lastIndexOf('/') + 1);
        // Spring 负责按照 RFC 兼容方式编码中文文件名，避免手工拼接响应头。
        String contentDisposition = ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString();
        response.setHeader("Content-Disposition", contentDisposition);
        // 不提前设置 Content-Length：远端校验失败时，仍可返回标准 JSON 错误。
        // Tauri 使用任务清单大小校验下载流，并在保存成功后发送确认。
        service.download(owner, id, itemId, response.getOutputStream());
    }
    /** 确认下载条目是否已由 Tauri 成功写入本地磁盘。 */
    @PostMapping("/transfers/{id}/items/{itemId}/confirm")
    public Response<Void> confirm(
            @PathVariable String id,
            @PathVariable String itemId,
            @RequestBody SftpRequests.ConfirmDownload request,
            Principal principal
    ) {
        // id 表示 transferId，itemId 表示该任务清单中的具体下载条目。
        service.confirm(owner(principal), id, itemId, request);
        return Response.success("确认成功", null);
    }
}
