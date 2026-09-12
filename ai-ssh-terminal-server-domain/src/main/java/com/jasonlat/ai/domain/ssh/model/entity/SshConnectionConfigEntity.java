package com.jasonlat.ai.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionConfigEntity {

    /** 关联的连接ID */
    private String connectionId;
    /** 连接超时时间(秒) */
    private Integer connectTimeout;
    /** 保活间隔(秒) */
    private Integer keepaliveInterval;
    /** 连接后执行的启动命令 */
    private String startupCommand;
    /** 是否压缩:0-否,1-是 */
    private Boolean compression;
    /** 严格主机密钥检查:0-否,1-是 */
    private Boolean strictHostKeyCheck;
    /** 已知主机密钥列表 */
    private String knownHosts;
    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 设置默认值
     */
    public void withDefaults() {
        if (connectTimeout == null) connectTimeout = 10;
        if (keepaliveInterval == null) keepaliveInterval = 60;
        if (compression == null) compression = false;
        if (strictHostKeyCheck == null) strictHostKeyCheck = true;
    }

}
