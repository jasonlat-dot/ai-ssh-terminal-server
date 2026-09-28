package com.jasonlat.ai.cases;

import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;

/** 本地存储临时下载门面，负责按 storageId 选择实现并完成链接验签。 */
public interface ILocalFileDownloadCase {

    /**
     * 打开一个已经签名的本地附件下载流。
     * 参数全部来自存储实现生成的临时 URL，Controller 不自行拼接本机路径。
     */
    TemporaryDownload open(
            String storageId,
            String objectKey,
            String fileName,
            long expiresAtEpochSecond,
            String signature);
}
