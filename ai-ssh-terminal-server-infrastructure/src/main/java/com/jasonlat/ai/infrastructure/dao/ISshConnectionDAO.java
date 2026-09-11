package com.jasonlat.ai.infrastructure.dao;

import com.jasonlat.ai.infrastructure.dao.po.SshConnectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SSH连接配置DAO
 *
 * @author jasonlat
 */
@Mapper
public interface ISshConnectionDAO {

    /**
     * 插入SSH连接配置
     *
     * @param po 连接配置对象
     */
    void insert(SshConnectionPO po);

    /**
     * 更新SSH连接配置
     *
     * @param po 连接配置对象
     */
    void update(SshConnectionPO po);

    /**
     * 根据连接ID删除SSH连接配置（逻辑删除）
     *
     * @param connectionId 连接唯一标识
     */
    void delete(@Param("connectionId") String connectionId);

    /**
     * 根据连接ID查询SSH连接配置
     *
     * @param connectionId 连接唯一标识
     * @return 连接配置对象
     */
    SshConnectionPO queryByConnectionId(@Param("connectionId") String connectionId);

    /**
     * 根据用户ID查询SSH连接配置列表
     *
     * @param userId 用户ID
     * @return 连接配置列表
     */
    List<SshConnectionPO> queryListByUserId(@Param("userId") String userId);

}
