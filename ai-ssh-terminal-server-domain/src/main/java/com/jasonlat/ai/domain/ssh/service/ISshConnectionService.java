package com.jasonlat.ai.domain.ssh.service;

import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.ConnectionStatusEnum;

import java.util.List;

public interface ISshConnectionService {


    /**
     * 创建SSH连接
     */
    void createConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * 更新SSH连接
     */
    void updateConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * 删除SSH连接
     */
    void deleteConnection(String connectionId);

    /**
     * 查询单个连接
     */
    SshConnectionEntity getConnection(String connectionId);

    /**
     * 查询用户的所有连接
     */
    List<SshConnectionEntity> getConnectionList(String userId);

    /**
     * 获取连接的高级配置
     */
    SshConnectionConfigEntity getConnectionConfig(String connectionId);

    /**
     * 建立SSH连接
     * @param connectionId 连接ID
     * @return 是否连接成功
     */
    boolean connect(String connectionId);

    /**
     * 断开SSH连接
     * @param connectionId 连接ID
     */
    void disconnect(String connectionId);

    /**
     * 检查连接是否活跃
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    boolean isConnected(String connectionId);

    /**
     * 探活并纠正数据库中记录的连接状态。
     * <p>
     * 当 SSH Session 实际已断开但 DB 仍标记为 CONNECTED 时，将其纠正为 DISCONNECTED，
     * 并清理基础设施层残留的 Session 引用与终端会话。供连接监测器与前端主动查询使用。
     *
     * @param connectionId 连接ID
     * @return 纠正后的最新连接状态
     */
    ConnectionStatusEnum checkAndRefreshStatus(String connectionId);


}
