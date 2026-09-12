package com.jasonlat.ai.domain.ssh.service;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * @author jasonlat
 * 2026-09-12  12:51
 */
@Slf4j
@Service
public class SshConnectionDomainService implements ISshConnectionDomainService {

    private final ISshConnectionRepository repository;
    private final ISshSessionPort sshSessionPort;

    public SshConnectionDomainService(ISshConnectionRepository repository, ISshSessionPort sshSessionPort) {
        this.repository = repository;
        this.sshSessionPort = sshSessionPort;
    }

    /**
     * 创建SSH连接
     */
    @Override
    public void createConnection(SshConnectionEntity connectionEntity, SshConnectionConfigEntity configEntity) {
        // 1. 字段合法性校验
        connectionEntity.validate();

        // 2. 生成连接ID
        if (connectionEntity.getConnectionId() == null || connectionEntity.getConnectionId().isBlank()) {
            connectionEntity.setConnectionId(UUID.randomUUID().toString());
        }

        // 3. 设置默认值
        connectionEntity.withDefaults();

        // 4. 保存连接
        repository.saveConnect(connectionEntity);

        // 5. 保存高级连接配置
        if (configEntity != null) {
            configEntity.setConnectionId(connectionEntity.getConnectionId());
            configEntity.withDefaults();
            repository.saveConnectionConfig(configEntity);
        }
    }

    /**
     * 更新SSH连接
     */
    @Override
    public void updateConnection(SshConnectionEntity connectionEntity, SshConnectionConfigEntity configEntity) {
        // 1. 字段合法性校验
        connectionEntity.validate();

        // 2. 检查连接是否存在，并获取原有数据
        SshConnectionEntity existing = repository.queryConnectionById(connectionEntity.getConnectionId());
        if (existing == null) {
            throw new AppException(ResponseCode.CONNECTION_NOT_FOUND);
        }

        // 3. 密码/私钥留空则保留原值
        if (connectionEntity.getPassword() == null || connectionEntity.getPassword().isEmpty()) {
            connectionEntity.setPassword(existing.getPassword());
        }
        if (connectionEntity.getPrivateKey() == null || connectionEntity.getPrivateKey().isEmpty()) {
            connectionEntity.setPrivateKey(existing.getPrivateKey());
        }
        // encrypted 保留原值
        if (connectionEntity.getEncrypted() == null) {
            connectionEntity.setEncrypted(existing.getEncrypted());
        }

        // 4. 更新连接
        repository.updateConnection(connectionEntity);

        // 5. 更新高级配置
        if (configEntity != null) {
            configEntity.setConnectionId(connectionEntity.getConnectionId());
            repository.saveConnectionConfig(configEntity);
        }

        log.info("SSH连接更新成功 connectionId={}", connectionEntity.getConnectionId());


    }

    /**
     * 删除SSH连接
     */
    @Override
    public void deleteConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }
        repository.deleteConnection(connectionId);
        log.info("SSH连接删除成功 connectionId={}", connectionId);

    }

    /**
     * 查询单个连接
     */
    @Override
    public SshConnectionEntity getConnection(String connectionId) {
        return repository.queryConnectionById(connectionId);
    }

    /**
     * 查询用户的所有连接
     */
    @Override
    public List<SshConnectionEntity> getConnectionList(String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        return repository.queryConnectionListByUserId(userId);

    }

    /**
     * 获取连接的高级配置
     */
    @Override
    public SshConnectionConfigEntity getConnectionConfig(String connectionId) {
        return repository.queryConnectionConfigById(connectionId);

    }

    /**
     * 建立SSH连接
     *
     * @param connectionId 连接ID
     * @return 是否连接成功
     */
    @Override
    public boolean connect(String connectionId) {
        // 1. 查询连接信息
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity == null) {
            throw new IllegalArgumentException("连接不存在");
        }

        // 2. 建立 SSH 连接
        boolean success = sshSessionPort.connect(
                connectionId,
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getPrivateKey()
        );

        // 3. 更新连接状态
        entity.setStatus(success ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED);
        repository.updateConnection(entity);

        return success;
    }

    /**
     * 断开SSH连接
     *
     * @param connectionId 连接ID
     */
    @Override
    public void disconnect(String connectionId) {
        // 1. 断开 SSH 连接
        sshSessionPort.disconnect(connectionId);

        // 2. 更新连接状态
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity != null && ConnectionStatusEnum.CONNECTED.equals(entity.getStatus())) {
            entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
            repository.updateConnection(entity);
        }
    }

    /**
     * 检查连接是否活跃
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    @Override
    public boolean isConnected(String connectionId) {
        return sshSessionPort.isConnected(connectionId);

    }
}
