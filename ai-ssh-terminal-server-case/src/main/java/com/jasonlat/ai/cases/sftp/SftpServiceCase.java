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
    public SftpServiceCase(ISftpService service, ISshConnectionRepository connections, String anonymousUserId) {
        this.service = service;
        this.connections = connections;
        this.anonymousUserId = anonymousUserId;
    }
    /** 未接登录的本地部署可以指定固定身份；置空即强制认证，不能用请求参数绕过。 */
    public String owner(String authenticatedUserId) {
        String result = authenticatedUserId == null ? anonymousUserId : authenticatedUserId;
        if (result == null || result.isBlank()) {
            throw new SftpException("SFTP_UNAUTHORIZED", "请先登录");
        }
        return result;
    }
    public SessionView open(String owner, SftpRequests.OpenSession request) {
        if (request == null
                || request.connectionId() == null
                || request.connectionId().isBlank()) {
            throw invalid();
        }
        SshConnectionEntity connection = connections.queryConnectionById(request.connectionId());
        if (connection == null || !Objects.equals(connection.getUserId(), owner)) {
            throw new SftpException("SFTP_NOT_FOUND", "连接不存在或无权访问");
        }
        return service.open(
                owner,
                connection,
                connections.queryConnectionConfigById(connection.getConnectionId()),
                request.rootPath()
        );
    }

    public SessionView session(String owner, String id) {
        return service.session(owner, id);
    }

    public void close(String owner, String id) {
        service.close(owner, id);
    }

    public List<Entry> list(String owner, String id, String path) {
        return service.list(owner, id, path);
    }

    public void mkdir(String owner, String id, SftpRequests.CreateDirectory request) {
        if (request == null || request.path() == null) {
            throw invalid();
        }
        service.mkdir(owner, id, request.path());
    }

    public void createFile(String owner, String id, SftpRequests.CreateFile request) {
        if (request == null || request.path() == null) {
            throw invalid();
        }
        service.createFile(owner, id, request.path());
    }

    public void delete(String owner, String id, String path) {
        if (path == null || path.isBlank()) {
            throw invalid();
        }
        service.delete(owner, id, path);
    }

    public TransferView create(String owner, SftpRequests.CreateTransfer request) {
        if (request == null) {
            throw invalid();
        }
        try {
            List<ManifestItem> items = request.items() == null
                    ? null
                    : request.items().stream().map(item -> {
                        if (item == null) {
                            throw invalid();
                        }
                        return new ManifestItem(
                                item.relativePath(),
                                Kind.valueOf(item.kind()),
                                item.size()
                        );
                    }).toList();

            Conflict conflict = request.conflict() == null
                    ? Conflict.FAIL
                    : Conflict.valueOf(request.conflict());
            CreateTransfer command = new CreateTransfer(
                    request.sftpSessionId(),
                    Direction.valueOf(request.direction()),
                    request.remotePath(),
                    conflict,
                    items
            );
            return service.create(owner, command);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 枚举转换失败和缺失必填字段都统一映射为客户端参数错误。
            throw invalid();
        }
    }

    public TransferView task(String owner, String id) {
        return service.task(owner, id);
    }

    public ItemView downloadItem(String owner, String id, String itemId) {
        return service.downloadItem(owner, id, itemId);
    }

    public List<TransferView> tasks(String owner, String sessionId) {
        return service.tasks(owner, sessionId);
    }

    public List<Progress> progress(String owner, String sessionId) {
        return service.progress(owner, sessionId);
    }

    public void cancel(String owner, String id) {
        service.cancel(owner, id);
    }

    public void upload(
            String owner,
            String id,
            String item,
            InputStream input,
            long length
    ) {
        service.upload(owner, id, item, input, length);
    }

    public void download(String owner, String id, String item, OutputStream output) {
        service.download(owner, id, item, output);
    }

    public void confirm(String owner, String id, String item, SftpRequests.ConfirmDownload request) {
        if (request == null || request.saved() == null) {
            throw invalid();
        }
        service.confirm(owner, id, item, request.saved());
    }

    private static SftpException invalid() {
        return new SftpException("SFTP_INVALID", "SFTP 请求参数不合法");
    }
}
