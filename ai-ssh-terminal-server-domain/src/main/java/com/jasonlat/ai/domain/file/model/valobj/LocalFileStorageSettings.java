package com.jasonlat.ai.domain.file.model.valobj;

import org.jspecify.annotations.NonNull;

/**
 * 本机文件系统存储使用的不可变配置。
 *
 * <p>本实现把附件内容写入后端进程所在机器，不依赖 MinIO、OSS 等外部服务。
 * 配置完整性延迟到真正选择该存储时检查，因此关闭本地存储不会影响应用启动。
 *
 * @param enabled 是否启用本地存储实例
 * @param storageId 存储实例的稳定标识，会写入文件元数据
 * @param rootDirectory 附件根目录；相对路径相对于后端进程工作目录
 * @param publicBaseUrl 可选的固定下载接口地址；为空时由 Web 层根据当前上传请求动态补全
 * @param signingSecret 下载地址签名密钥；为空时每次启动随机生成，仅影响重启前已签发的临时链接
 */
public record LocalFileStorageSettings(
        boolean enabled,
        String storageId,
        String rootDirectory,
        String publicBaseUrl,
        String signingSecret) {

    /** 避免配置对象被日志输出时泄露下载签名密钥。 */
    @Override
    public @NonNull String toString() {
        return "LocalFileStorageSettings[storageId=" + storageId + ", enabled=" + enabled + "]";
    }
}
