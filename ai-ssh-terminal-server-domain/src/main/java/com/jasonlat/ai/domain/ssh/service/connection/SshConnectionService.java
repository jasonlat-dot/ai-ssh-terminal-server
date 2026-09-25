package com.jasonlat.ai.domain.ssh.service.connection;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SSH 连接配置及底层传输会话领域服务。
 * <p>
 * connectionId 表示一份连接配置和一条可复用的底层 SSH Session；terminalSessionId
 * 表示某个窗口自己的 Shell Channel。多个终端可以共享 connectionId，但关闭任意一个
 * terminalSessionId 时不能影响同 connectionId 下的其他终端。
 *
 * @author jasonlat
 * 2026-09-12  12:51
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionService {

    private final ISshConnectionRepository repository;
    private final ISshSessionPort sshSessionPort;
    private final ITerminalSessionPort terminalSessionPort;

    public SshConnectionService(ISshConnectionRepository repository,
                                ISshSessionPort sshSessionPort,
                                ITerminalSessionPort terminalSessionPort) {
        this.repository = repository;
        this.sshSessionPort = sshSessionPort;
        this.terminalSessionPort = terminalSessionPort;
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
        // 删除连接配置会使所有窗口失去归属信息，因此仍有任意活动终端时拒绝删除。
        if (terminalSessionPort.hasActiveSessions(connectionId)) {
            throw new IllegalArgumentException("该连接仍被其他窗口使用，请先关闭所有终端窗口");
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
     * 建立或复用 connectionId 对应的底层 SSH 连接。
     * 新窗口再次调用此方法时，基础设施层会复用健康 Session，随后 openTerminal 再创建
     * 独立 ChannelShell。
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
     * 请求断开底层 SSH 连接。
     * <p>
     * 调用方通常会先关闭自己持有的 terminalSessionId；只有同 connectionId 下已经没有
     * 活动终端时才真正断开 JSch Session，避免一个窗口把其他窗口一起踢下线。
     *
     * @param connectionId 连接ID
     */
    @Override
    public void disconnect(String connectionId) {
        /*
         * 一个窗口关闭时，它会先关闭自己的 terminalSessionId，再调用 disconnect。
         * 如果同一个 connectionId 下仍有其他窗口的 ChannelShell，必须保留共享的
         * 底层 SSH Session，否则会把其他窗口一起踢下线。
         */
        if (terminalSessionPort.hasActiveSessions(connectionId)) {
            log.info("SSH连接仍被其他终端使用，跳过底层断开 connectionId={}", connectionId);
            return;
        }
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

    @Override
    public ConnectionStatusEnum checkAndRefreshStatus(String connectionId) {
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity == null) {
            return ConnectionStatusEnum.DISCONNECTED;
        }

        boolean actuallyConnected = sshSessionPort.isConnected(connectionId);

        // 实际已断开：纠正 DB 状态并清理残留 Session 引用
        if (!actuallyConnected && entity.getStatus() == ConnectionStatusEnum.CONNECTED) {
            log.warn("SSH连接已断开（监测发现），纠正状态 connectionId={}", connectionId);
            sshSessionPort.disconnect(connectionId); // 清理 sessions map 中的残留引用
            entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
            repository.updateConnection(entity);
        }

        return entity.getStatus();
    }

}
