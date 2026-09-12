package com.jasonlat.ai.infrastructure.adapter.repository;

import com.jasonlat.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.jasonlat.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.jasonlat.ai.infrastructure.dao.ISshConnectionConfigDAO;
import com.jasonlat.ai.infrastructure.dao.ISshConnectionDAO;
import com.jasonlat.ai.infrastructure.dao.po.SshConnectionConfigPO;
import com.jasonlat.ai.infrastructure.dao.po.SshConnectionPO;
import com.jasonlat.ai.infrastructure.security.PasswordEncryptor;
import com.jasonlat.ai.types.snow.SnowflakeIdGenerator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author jasonlat
 * 2026-09-12  13:23
 */
@Repository
public class SshConnectionRepository implements ISshConnectionRepository {

    @Resource
    private ISshConnectionDAO sshConnectionDAO;
    @Resource
    private ISshConnectionConfigDAO sshConnectionConfigDAO;
    @Resource
    private PasswordEncryptor passwordEncryptor;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 保存SSH连接配置
     */
    @Override
    public void saveConnect(SshConnectionEntity connectionEntity) {
        sshConnectionDAO.insert(toPO(connectionEntity));
    }

    /**
     * 保存/更新高级配置
     */
    @Override
    public void saveConnectionConfig(SshConnectionConfigEntity configEntity) {
        sshConnectionConfigDAO.insertOrUpdate(toConfigPO(configEntity));
    }

    /**
     * 根据连接ID查询
     */
    @Override
    public SshConnectionEntity queryConnectionById(String connectionId) {
        SshConnectionPO po = sshConnectionDAO.queryByConnectionId(connectionId);
        return po != null ? toEntity(po) : null;
    }

    /**
     * 更新SSH连接配置
     */
    @Override
    public void updateConnection(SshConnectionEntity connectionEntity) {
        sshConnectionDAO.update(toPO(connectionEntity));
    }

    /**
     * 删除SSH连接配置
     */
    @Override
    public void deleteConnection(String connectionId) {
        sshConnectionDAO.delete(connectionId);
    }

    /**
     * 查询用户的所有连接
     */
    @Override
    public List<SshConnectionEntity> queryConnectionListByUserId(String userId) {
        return sshConnectionDAO.queryListByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    /**
     * 根据连接ID查询高级配置
     */
    @Override
    public SshConnectionConfigEntity queryConnectionConfigById(String connectionId) {
        SshConnectionConfigPO po = sshConnectionConfigDAO.queryByConnectionId(connectionId);
        return po != null ? toConfigEntity(po) : null;
    }

    // ========== Entity <-> PO 转换 ==========

    private SshConnectionPO toPO(SshConnectionEntity entity) {
        // 加密密码和私钥
        String encryptedPassword = entity.getPassword();
        String encryptedPrivateKey = entity.getPrivateKey();
        Integer encryptedFlag = entity.getEncrypted();

        if (encryptedPassword != null && !encryptedPassword.isEmpty() && !passwordEncryptor.isEncrypted(encryptedPassword)) {
            encryptedPassword = passwordEncryptor.encrypt(encryptedPassword);
            encryptedFlag = 1;
        }
        if (encryptedPrivateKey != null && !encryptedPrivateKey.isEmpty() && !passwordEncryptor.isEncrypted(encryptedPrivateKey)) {
            encryptedPrivateKey = passwordEncryptor.encrypt(encryptedPrivateKey);
            encryptedFlag = 1;
        }

        return SshConnectionPO.builder()
                .id(snowflakeIdGenerator.nextId())
                .connectionId(entity.getConnectionId())
                .connectionName(entity.getConnectionName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .authType(entity.getAuthType() != null ? entity.getAuthType().getCode() : AuthTypeEnum.PASSWORD.getCode())
                .password(encryptedPassword)
                .privateKey(encryptedPrivateKey)
                .encrypted(encryptedFlag)
                .status(entity.getStatus() != null ? entity.getStatus().getCode() : ConnectionStatusEnum.DISCONNECTED.getCode())
                .userId(entity.getUserId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private SshConnectionEntity toEntity(SshConnectionPO po) {
        // 解密密码和私钥
        String password = po.getPassword();
        String privateKey = po.getPrivateKey();

        if (po.getEncrypted() != null && po.getEncrypted() == 1) {
            if (password != null && !password.isEmpty()) {
                password = passwordEncryptor.decrypt(password);
            }
            if (privateKey != null && !privateKey.isEmpty()) {
                privateKey = passwordEncryptor.decrypt(privateKey);
            }
        }

        return SshConnectionEntity.builder()
                .connectionId(po.getConnectionId())
                .connectionName(po.getConnectionName())
                .host(po.getHost())
                .port(po.getPort())
                .username(po.getUsername())
                .authType(AuthTypeEnum.fromCode(po.getAuthType()))
                .password(password)
                .privateKey(privateKey)
                .encrypted(po.getEncrypted())
                .status(ConnectionStatusEnum.fromCode(po.getStatus()))
                .userId(po.getUserId())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private SshConnectionConfigPO toConfigPO(SshConnectionConfigEntity entity) {
        return SshConnectionConfigPO.builder()
                .id(snowflakeIdGenerator.nextId())
                .connectionId(entity.getConnectionId())
                .connectTimeout(entity.getConnectTimeout())
                .keepaliveInterval(entity.getKeepaliveInterval())
                .startupCommand(entity.getStartupCommand())
                .compression(booleanToInt(entity.getCompression()))
                .strictHostKeyCheck(booleanToInt(entity.getStrictHostKeyCheck()))
                .knownHosts(entity.getKnownHosts())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private SshConnectionConfigEntity toConfigEntity(SshConnectionConfigPO po) {
        return SshConnectionConfigEntity.builder()
                .connectionId(po.getConnectionId())
                .connectTimeout(po.getConnectTimeout())
                .keepaliveInterval(po.getKeepaliveInterval())
                .startupCommand(po.getStartupCommand())
                .compression(intToBoolean(po.getCompression()))
                .strictHostKeyCheck(intToBoolean(po.getStrictHostKeyCheck()))
                .knownHosts(po.getKnownHosts())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private Integer booleanToInt(Boolean val) {
        return Boolean.TRUE.equals(val) ? 1 : 0;
    }

    private Boolean intToBoolean(Integer val) {
        return val != null && val == 1;
    }
}
