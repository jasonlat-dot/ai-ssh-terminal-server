package com.jasonlat.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH连接配置持久化对象
 *
 * @author jasonlat
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionPO {

    /** 自增主键 */
    private Long id;
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
    private Integer authType;
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

}
