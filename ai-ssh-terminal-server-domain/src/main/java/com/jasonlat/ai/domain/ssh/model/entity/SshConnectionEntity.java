package com.jasonlat.ai.domain.ssh.model.entity;

import com.jasonlat.ai.domain.ssh.model.valobj.AuthTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionEntity {

    /** 连接唯一标识(UUID) */
    private String connectionId;
    /** 连接名称 */
    private String connectionName;
    /** 主机地址 */
    private String host;
    /** 端口号 */
    private Integer port;
    /** 用户名 */
    private String username;
    /** 认证类型:1-密码,2-私钥 */
    private AuthTypeEnum authType;
    /** 密码(加密存储) */
    private String password;
    /** 私钥内容(加密存储) */
    private String privateKey;
    /** 是否加密:0-否,1-是 */
    private Integer encrypted;
    /** 用户ID */
    private String userId;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
    /** 逻辑删除:0-未删除,1-已删除 */
    private Integer deleted;
    /**
     * 校验必填字段
     */
    public void validate() {
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        if (port == null || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口号不合法");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
    }

    public void withDefaults() {
        if (port == null) {
            port = 22;
        }
        if (encrypted == null) {
            encrypted = 1;
        }
        if (userId == null || userId.isBlank()) {
            userId = "defaultUser";
        }
    }
}
