package com.jasonlat.ai.domain.file.adapter.repository;

import com.jasonlat.ai.domain.file.model.entity.FileAssetEntity;

public interface IFileAssetRepository {
    void create(FileAssetEntity asset);

    void update(FileAssetEntity asset);
}
