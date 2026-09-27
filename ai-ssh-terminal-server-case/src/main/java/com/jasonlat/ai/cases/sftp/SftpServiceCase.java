package com.jasonlat.ai.cases.sftp;

import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.*;
import com.jasonlat.ai.domain.sftp.service.ISftpService;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.trigger.api.dto.sftp.SftpRequests;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

/** 统一应用门面：身份与连接归属校验、API 命令转换、调用领域服务。由 app 配置装配。 */
public class SftpServiceCase {
    private final ISftpService service;
    private final ISshConnectionRepository connections;
    private final String anonymousUserId;
    /** 注入领域服务、SSH 配置仓储以及可选的单机匿名身份。 */
    public SftpServiceCase(ISftpService service, ISshConnectionRepository connections, String anonymousUserId) {
        this.service = service;
        this.connections = connections;
        this.anonymousUserId = anonymousUserId;
    }
    /** 未接登录的本地部署可以指定固定身份；置空即强制认证，不能用请求参数绕过。 */
    public String owner(String authenticatedUserId) {
        // 已登录时必须使用 Principal 中的身份；只有没有 Principal 时才允许使用部署配置的匿名身份。
        String result = authenticatedUserId == null ? anonymousUserId : authenticatedUserId;
        // 两种身份来源都为空，说明当前部署要求登录，直接拒绝后续资源访问。
        if (result == null || result.isBlank()) {
            throw new SftpException("SFTP_UNAUTHORIZED", "请先登录");
        }
        return result;
    }
    /**
     * 校验连接归属后打开 SFTP 会话。
     *
     * <p>这里只接受 connectionId，不接受客户端上传密码、私钥或 userId。凭据始终从服务端仓储读取。</p>
     */
    public SessionView open(String owner, SftpRequests.OpenSession request) {
        // connectionId 是查找服务端 SSH 配置的唯一依据，空值不能继续进入仓储层。
        if (request == null
                || request.connectionId() == null
                || request.connectionId().isBlank()) {
            throw invalid();
        }
        // 这里只按引用读取凭据，密码和私钥始终留在服务端，不接受前端临时传入。
        SshConnectionEntity connection = connections.queryConnectionById(request.connectionId());
        // 同时比较连接记录的 userId，避免用户通过猜测 connectionId 使用他人的服务器。
        if (connection == null || !Objects.equals(connection.getUserId(), owner)) {
            throw new SftpException("SFTP_NOT_FOUND", "连接不存在或无权访问");
        }
        // 凭据实体负责认证信息，配置实体负责 known_hosts、压缩、超时等连接选项。
        return service.open(
                owner,
                connection,
                connections.queryConnectionConfigById(connection.getConnectionId()),
                request.rootPath()
        );
    }

    /** 查询 owner 自己的文件管理会话。 */
    public SessionView session(String owner, String id) {
        // 领域服务会用 owner + sftpSessionId 二次校验归属。
        return service.session(owner, id);
    }

    /** 关闭会话并释放独立的 JSch SSH 连接。 */
    public void close(String owner, String id) {
        // close 不只是删除内存记录，还会关闭底层 SSH Session 并中止活动 Channel。
        service.close(owner, id);
    }

    /** 列出远程目录；路径规范化与根目录边界校验由领域层完成。 */
    public List<Entry> list(String owner, String id, String path) {
        // path 可以为空；为空时领域层会回退到该 SFTP 会话的 rootPath。
        return service.list(owner, id, path);
    }

    /** 校验创建目录请求结构，再把纯路径命令交给领域层。 */
    public void mkdir(String owner, String id, SftpRequests.CreateDirectory request) {
        // HTTP DTO 在进入领域层前先做结构校验，领域层只接收明确的 path 字符串。
        if (request == null || request.path() == null) {
            throw invalid();
        }
        service.mkdir(owner, id, request.path());
    }

    /** 校验创建文件请求结构；领域层保证不覆盖已有条目。 */
    public void createFile(String owner, String id, SftpRequests.CreateFile request) {
        // 这里只校验字段是否存在；路径越界、父目录符号链接和同名冲突由领域层统一处理。
        if (request == null || request.path() == null) {
            throw invalid();
        }
        service.createFile(owner, id, request.path());
    }

    /** 校验删除路径不为空；实际文件类型判断与安全限制由领域层完成。 */
    public void delete(String owner, String id, String path) {
        // 空路径不能代表根目录，避免含糊请求意外落到会话 rootPath。
        if (path == null || path.isBlank()) {
            throw invalid();
        }
        service.delete(owner, id, path);
    }

    /**
     * 把 HTTP DTO 转换为强类型领域命令并创建传输任务。
     *
     * <p>字符串枚举在本层转换，非法 direction、kind 或 conflict 统一返回 SFTP_INVALID，
     * 避免 JSch 层接触不可信的原始请求字段。</p>
     */
    public TransferView create(String owner, SftpRequests.CreateTransfer request) {
        // 整个请求缺失时无法判断方向、会话或远程路径，直接返回统一参数错误。
        if (request == null) {
            throw invalid();
        }
        try {
            // 上传任务由 Tauri 提交本地扫描清单；下载任务不需要客户端清单，因此允许 items 为 null。
            List<ManifestItem> items = request.items() == null
                    ? null
                    : request.items().stream().map(item -> {
                        // 清单中出现 null 条目通常表示客户端序列化或调用错误，不能静默忽略。
                        if (item == null) {
                            throw invalid();
                        }
                        // 把字符串 kind 转为领域枚举，使后面的业务逻辑不再处理任意字符串。
                        return new ManifestItem(
                                item.relativePath(),
                                Kind.valueOf(item.kind()),
                                item.size()
                        );
                    }).toList();

            // 未传冲突策略时采用最安全的 FAIL：发现同名项立即失败，不覆盖服务器文件。
            Conflict conflict = request.conflict() == null
                    ? Conflict.FAIL
                    : Conflict.valueOf(request.conflict());
            // 将 HTTP DTO 汇总成领域命令；Direction.valueOf 同样会拒绝未知的传输方向。
            CreateTransfer command = new CreateTransfer(
                    request.sftpSessionId(),
                    Direction.valueOf(request.direction()),
                    request.remotePath(),
                    conflict,
                    items
            );
            // 领域层继续校验会话归属、路径边界、配额、清单大小，并生成传输任务快照。
            return service.create(owner, command);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 枚举转换失败和缺失必填字段都统一映射为客户端参数错误。
            throw invalid();
        }
    }

    /** 查询单个传输任务，领域层会同时校验任务所有者。 */
    public TransferView task(String owner, String id) {
        // id 在这里表示 transferId，而不是 sftpSessionId 或 SSH connectionId。
        return service.task(owner, id);
    }

    /** 在写响应头之前读取下载条目元数据，用于生成安全的下载文件名。 */
    public ItemView downloadItem(String owner, String id, String itemId) {
        // 先取元数据但不开始传输，用于 Controller 安全设置 Content-Disposition 文件名。
        return service.downloadItem(owner, id, itemId);
    }

    /** 查询一个文件管理会话下的全部传输任务。 */
    public List<TransferView> tasks(String owner, String sessionId) {
        // sessionId 表示 sftpSessionId；结果只包含该文件管理窗口创建的任务。
        return service.tasks(owner, sessionId);
    }

    /** 读取自上次轮询后的进度事件；主要供 SSE 事件流内部使用。 */
    public List<Progress> progress(String owner, String sessionId) {
        // 只返回轻量进度快照，避免 SSE 每次推送复制完整任务清单。
        return service.progress(owner, sessionId);
    }

    /** 请求取消任务；传输线程通过 BooleanSupplier 协作式结束。 */
    public void cancel(String owner, String id) {
        // 取消是协作式的：先标记任务，再由进度回调或 Channel 关闭让 JSch 尽快退出。
        service.cancel(owner, id);
    }

    /**
     * 将当前 HTTP 请求体同步传给领域服务。
     *
     * <p>必须在 Controller 请求线程内消费完 InputStream，不能把 Servlet 流交给后台线程。</p>
     */
    public void upload(
            String owner,
            String id,
            String item,
            InputStream input,
            long length
    ) {
        // InputStream 属于当前 Servlet 请求，领域服务必须在本方法返回前同步消费完毕。
        service.upload(owner, id, item, input, length);
    }

    /** 将远程文件同步写入 HTTP 响应流，全程采用流式复制。 */
    public void download(String owner, String id, String item, OutputStream output) {
        // OutputStream 直接连接 HTTP 响应；领域层不会缓存完整远程文件。
        service.download(owner, id, item, output);
    }

    /** 校验客户端保存确认并推进下载条目最终状态。 */
    public void confirm(String owner, String id, String item, SftpRequests.ConfirmDownload request) {
        // Boolean 使用包装类型是为了区分 false 与请求中根本没有 saved 字段。
        if (request == null || request.saved() == null) {
            throw invalid();
        }
        // 只有 Tauri 完成关闭本地文件后才发送 true，后端据此把 SENT 推进到 COMPLETED。
        service.confirm(owner, id, item, request.saved());
    }

    /** 生成统一的 HTTP 参数错误，避免向客户端泄露内部枚举解析异常。 */
    private static SftpException invalid() {
        return new SftpException("SFTP_INVALID", "SFTP 请求参数不合法");
    }
}
