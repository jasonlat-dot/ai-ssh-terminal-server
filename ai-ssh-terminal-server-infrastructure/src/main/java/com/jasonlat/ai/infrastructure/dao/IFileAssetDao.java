package com.jasonlat.ai.infrastructure.dao;

import com.jasonlat.ai.infrastructure.dao.po.FileAssetPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFileAssetDao {
    FileAssetPO findById(@Param("fileId") String fileId);

    int insert(FileAssetPO po);

    int update(FileAssetPO po);
}
