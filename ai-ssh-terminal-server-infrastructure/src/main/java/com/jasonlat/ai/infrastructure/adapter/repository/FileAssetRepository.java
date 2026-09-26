package com.jasonlat.ai.infrastructure.adapter.repository;

import com.jasonlat.ai.domain.file.adapter.repository.IFileAssetRepository;
import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;
import com.jasonlat.ai.infrastructure.dao.IFileAssetDao;
import com.jasonlat.ai.infrastructure.dao.po.FileAssetPO;
import org.springframework.stereotype.Repository;

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
