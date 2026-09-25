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

}
