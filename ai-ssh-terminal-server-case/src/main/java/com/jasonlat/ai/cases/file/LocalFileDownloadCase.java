package com.jasonlat.ai.cases.file;

import com.jasonlat.ai.cases.ILocalFileDownloadCase;
import com.jasonlat.ai.domain.file.model.valobj.TemporaryDownload;
import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.service.ITemporaryDownloadStorageService;
import com.jasonlat.ai.domain.file.service.storage.resolver.IObjectStorageResolver;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/** 根据持久化 storageId 路由临时下载，避免 Web 层直接依赖本地存储具体实现。 */
@Service
public class LocalFileDownloadCase implements ILocalFileDownloadCase {
    /** 根据持久化 storageId 查找已启用存储实现的解析器。 */
    private final IObjectStorageResolver storageResolver;

    /** 注入统一存储解析器。 */
    public LocalFileDownloadCase(IObjectStorageResolver storageResolver) {
        this.storageResolver = storageResolver;
    }

    /** 选择支持临时 HTTP 下载的存储实现，并转交签名校验及文件打开操作。 */
    @Override
    public TemporaryDownload open(
            String storageId,
            String objectKey,
            String fileName,
            long expiresAtEpochSecond,
            String signature) {
        // resolve 会检查该 storageId 是否已启用，并触发具体实现的配置校验。
        IObjectStorageService storage = storageResolver.resolve(storageId);
        // 只有需要后端代理下载的存储才能进入该接口；MinIO 应使用自己生成的预签名 URL。
        if (!(storage instanceof ITemporaryDownloadStorageService temporaryDownloadStorage)) {
            throw new AppException(ResponseCode.FILE_DOWNLOAD_LINK_INVALID);
        }
        // objectKey 不在门面层拼接磁盘路径，具体实现负责验签和根目录边界检查。
        return temporaryDownloadStorage.openTemporaryDownload(
                objectKey, fileName, expiresAtEpochSecond, signature);
    }
}
