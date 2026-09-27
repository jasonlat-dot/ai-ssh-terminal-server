package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH连接响应DTO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionResponseDTO {

    /** 连接ID */
    private String connectionId;

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口号 */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 是否加密 */
    private Integer encrypted;

    /** 用户ID */
    private String userId;

    /** 创建时间 */
    private String createdAt;

    /** 更新时间 */
    private String updatedAt;

    /** 连接超时时间(秒) */
    private Integer connectTimeout;

    /** 保活间隔(秒) */
    private Integer keepaliveInterval;

    /** 连接后执行的启动命令 */
    private String startupCommand;

    /** 是否启用压缩 */
    private Boolean compression;

    /** 是否严格校验服务器主机密钥 */
    private Boolean strictHostKeyCheck;

    /**
     * OpenSSH known_hosts 文件正文，用于校验服务器身份，不是用户登录私钥。
     * 支持 ssh-ed25519、ECDSA nistp256/384/521 和 RSA-SHA2 主机签名。
     */
    private String knownHosts;

}
