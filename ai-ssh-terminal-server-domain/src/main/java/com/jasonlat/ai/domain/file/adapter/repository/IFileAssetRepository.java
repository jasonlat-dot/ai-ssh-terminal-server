package com.jasonlat.ai.domain.file.adapter.repository;

import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;

/** 文件元数据持久化端口，不负责读写文件正文。 */
public interface IFileAssetRepository {
    /** 上传前创建 UPLOADING 记录，先保存对象位置，方便处理中断后的排查。 */
    void create(FileAssetEntity asset);

    /** 写入上传结果或补偿结果，包括摘要、版本号、状态和失败码。 */
    void update(FileAssetEntity asset);
}
