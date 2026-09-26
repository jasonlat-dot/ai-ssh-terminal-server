package com.jasonlat.ai.infrastructure.adapter.repository;

import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.infrastructure.dao.IFileAssetDao;
import com.jasonlat.ai.infrastructure.dao.po.FileAssetPO;
import org.springframework.stereotype.Repository;

/** 将领域文件元数据映射为数据库记录，对象正文不经过数据库。 */
@Repository
public class FileAssetRepository implements IFileAssetRepository {
    private final IFileAssetDao dao;

    public FileAssetRepository(IFileAssetDao dao) {
        this.dao = dao;
    }

    @Override
    public void create(FileAssetEntity asset) {
        requireChanged(dao.insert(toPO(asset)));
    }

    @Override
    public void update(FileAssetEntity asset) {
        requireChanged(dao.update(toPO(asset)));
    }

    /** 确认唯一文件记录写入成功，避免无匹配记录时仍向上报告成功。 */
    private void requireChanged(int rows) {
        if (rows != 1) throw new IllegalStateException("文件元数据写入失败");
    }

    private FileAssetPO toPO(FileAssetEntity asset) {
        FileAssetPO po = new FileAssetPO();
        po.setFileId(asset.getFileId());
        po.setOwnerId(asset.getOwnerId());
        po.setOriginalName(asset.getOriginalName());
        po.setContentType(asset.getContentType());
        po.setSize(asset.getSize());
        po.setSha256(asset.getSha256());
        // 将领域中的存储位置值对象展开为表字段，后续可直接按实例和对象路径排查。
        po.setStorageId(asset.getLocation().storageId());
        po.setBucket(asset.getLocation().bucket());
        po.setObjectKey(asset.getLocation().objectKey());
        po.setObjectVersion(asset.getLocation().versionId());
        po.setEtag(asset.getEtag());
        po.setStatus(asset.getStatus().name());
        po.setErrorCode(asset.getErrorCode());
        return po;
    }
}
