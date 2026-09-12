package com.jasonlat.ai.domain.ssh.adapter.repository;

import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;

import java.util.List;

/**
 * SSH连接仓储接口（领域层定义，基础设施层实现）
 */
public interface ISshConnectionRepository {

    /**
     * 保存SSH连接配置
     */
    void saveConnect(SshConnectionEntity connectionEntity);

    /**
     * 保存/更新高级配置
     */
    void saveConnectionConfig(SshConnectionConfigEntity configEntity);

    /**
     * 根据连接ID查询
     */
    SshConnectionEntity queryConnectionById(String connectionId);

    /**
     * 更新SSH连接配置
     */
    void updateConnection(SshConnectionEntity connectionEntity);

    /**
     * 删除SSH连接配置
     */
    void deleteConnection(String connectionId);

    /**
     * 查询用户的所有连接
     */
    List<SshConnectionEntity> queryConnectionListByUserId(String userId);

    /**
     * 根据连接ID查询高级配置
     */
    SshConnectionConfigEntity queryConnectionConfigById(String connectionId);
}
