package com.jasonlat.ai.domain.ssh.service.monitor;

import com.jasonlat.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.jasonlat.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;

/**
 * SSH 连接监测器
 * <p>
 * 定时扫描内存中所有已注册的 SSH 连接，对每条连接进行探活：
 * 一旦发现 Session 实际已断开但 DB 仍标记为 CONNECTED，立即纠正状态为 DISCONNECTED，
 * 并清理基础设施层残留的 Session 引用与终端会话。
 * <p>
 * 这样可以解决"SSH 长期空闲断开后无人感知、AI 执行命令时报会话不可用"的问题。
 */
@Slf4j
@Component
public class SshConnectionMonitor {

    @Resource
    private ISshSessionPort sshSessionPort;

    @Resource
    private ISshConnectionService sshConnectionService;

    /**
     * 每 30 秒扫描一次所有内存中已注册的 SSH 连接。
     * <p>
     * 仅遍历 {@link ISshSessionPort#getActiveConnectionIds()} 返回的快照，
     * 不查全表，开销可控。探活逻辑委托给领域服务的 checkAndRefreshStatus。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void monitorConnections() {
        Set<String> activeIds = sshSessionPort.getActiveConnectionIds();
        if (activeIds == null || activeIds.isEmpty()) {
            return;
        }

        for (String connectionId : activeIds) {
            try {
                ConnectionStatusEnum latest = sshConnectionService.checkAndRefreshStatus(connectionId);
                if (latest == ConnectionStatusEnum.DISCONNECTED) {
                    log.info("连接监测：connectionId={} 状态={}", connectionId, latest.getDesc());
                }
            } catch (Exception e) {
                log.warn("连接监测异常 connectionId={} reason={}", connectionId, e.getMessage());
            }
        }
    }
}
