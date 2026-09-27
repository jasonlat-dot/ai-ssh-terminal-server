package com.jasonlat.ai.trigger.api.dto.sftp;

import java.util.List;

/** Tauri 仅提交连接引用、路径和清单；身份来自服务端 Principal，不接受客户端 userId。 */
public final class SftpRequests {
    private SftpRequests() { }
    public record OpenSession(String connectionId, String rootPath) { }
    public record CreateDirectory(String path) { }
    public record Item(String relativePath, String kind, long size) { }
    public record CreateTransfer(String sftpSessionId, String direction, String remotePath,
                                 String conflict, List<Item> items) { }
    /** saved 只表示 Tauri 已成功关闭本地文件或创建目录，不替代后端的传输结果。 */
    public record ConfirmDownload(Boolean saved) { }
}
