package com.jasonlat.ai.infrastructure.dao;

import com.jasonlat.ai.infrastructure.dao.po.FileAssetPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IFileAssetDao {
    int insert(FileAssetPO po);

    int update(FileAssetPO po);
}
