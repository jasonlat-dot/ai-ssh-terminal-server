package com.jasonlat.ai.trigger.api.dto.sftp;

import java.util.List;

/**
 * SFTP HTTP 接口使用的请求对象。
 *
 * <p>Tauri 只提交连接引用、路径和文件清单。用户身份始终来自服务端 {@code Principal}，
 * 接口不接收客户端传入的 userId，避免调用方伪造资源归属。</p>
 */
public final class SftpRequests {

    private SftpRequests() {
    }

    /**
     * 打开独立的文件管理会话。
     *
     * @param connectionId 已保存的 SSH 连接 ID，服务端会再次校验连接归属
     * @param rootPath     允许浏览的远程根目录；为空时使用 SSH 账号默认目录
     */
    public record OpenSession(
            String connectionId,
            String rootPath
    ) {
    }

    /**
     * 在当前会话允许访问的范围内创建远程目录。
     *
     * @param path 待创建的远程绝对路径
     */
    public record CreateDirectory(
            String path
    ) {
    }

    /**
     * 在当前会话允许访问的范围内创建空文件。
     *
     * @param path 待创建的远程绝对路径；目标已存在时创建失败，不会覆盖原文件
     */
    public record CreateFile(
            String path
    ) {
    }

    /**
     * 客户端扫描得到的单个传输清单条目。
     *
     * @param relativePath 相对于本次传输清单根目录的路径，不能包含越级片段
     * @param kind         条目类型，目前只接受 {@code FILE} 或 {@code DIRECTORY}
     * @param size         文件声明大小，单位为字节；目录必须为 0
     */
    public record Item(
            String relativePath,
            String kind,
            long size
    ) {
    }

    /**
     * 创建上传或下载任务。
     *
     * @param sftpSessionId 文件管理会话 ID
     * @param direction     传输方向：{@code UPLOAD} 或 {@code DOWNLOAD}
     * @param remotePath    上传时表示目标目录，下载时表示选中的远程文件或目录
     * @param conflict      同名文件策略：{@code FAIL}、{@code SKIP} 或 {@code REPLACE}
     * @param items         上传清单；下载清单由服务端扫描生成
     */
    public record CreateTransfer(
            String sftpSessionId,
            String direction,
            String remotePath,
            String conflict,
            List<Item> items
    ) {
    }

    /**
     * 确认下载内容是否成功保存到客户端。
     *
     * <p>{@code saved} 只表示 Tauri 已成功关闭本地文件或创建目录，不替代后端对远程读取、
     * 字节数和任务状态的校验。</p>
     *
     * @param saved {@code true} 表示客户端落盘成功，{@code false} 表示本地保存失败
     */
    public record ConfirmDownload(
            Boolean saved
    ) {
    }
}
