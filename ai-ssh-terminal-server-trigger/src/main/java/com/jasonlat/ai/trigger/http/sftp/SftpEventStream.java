package com.jasonlat.ai.trigger.http.sftp;

import com.jasonlat.ai.cases.sftp.SftpServiceCase;
import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Progress;
import jakarta.annotation.PreDestroy;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpPolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SFTP 任务进度的 SSE 发布器。
 *
 * <p>一个文件管理窗口只保留一个订阅。SSE 仅用于展示进度，不拥有传输任务生命周期；
 * 浏览器断线后重新订阅会收到最新快照，不会重新创建或取消文件任务。</p>
 */
@Component
@Slf4j
public class SftpEventStream {
    private final SftpServiceCase service;
    private final long intervalMillis;
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    /** 注入进度查询门面，并读取 SSE 快照发布间隔。 */
    public SftpEventStream(SftpServiceCase service, SftpPolicy policy) {
        this.service = service;
        this.intervalMillis = policy.progressPublishInterval().toMillis();
    }
    /**
     * 为文件管理会话创建唯一 SSE 订阅。
     *
     * <p>同一 sessionId 再次订阅时关闭旧连接，避免页面重连后残留多个发布线程。</p>
     */
    public SseEmitter open(String owner, String sessionId) {
        service.session(owner, sessionId);
        log.info("SFTP SSE 订阅开始 userId={} sessionId={}", owner, sessionId);
        Subscription next = new Subscription(owner, sessionId);
        Subscription previous = subscriptions.put(sessionId, next);
        if (previous != null) {
            previous.close();
        }
        next.thread.start();
        return next.emitter;
    }
    private final class Subscription {
        final String owner;
        final String sessionId;
        final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread thread;
        /** 创建尚未启动的虚拟线程，并注册所有 SSE 结束回调。 */
        Subscription(String owner, String sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.thread = Thread.ofVirtual().name("sftp-progress-" + sessionId).unstarted(this::run);
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(error -> close());
        }
        /** 按策略间隔发布变化后的进度；无变化时仍定期发送注释心跳。 */
        void run() {
            List<Progress> previous = null;
            long heartbeatAt = 0;
            try {
                while (!closed.get()) {
                    List<Progress> snapshot = service.progress(owner, sessionId)
                            .stream()
                            .sorted(java.util.Comparator.comparing(Progress::transferId))
                            .toList();
                    if (!snapshot.equals(previous)) {
                        emitter.send(SseEmitter.event().name("progress").data(snapshot));
                        previous = snapshot;
                    }
                    long now = System.currentTimeMillis();
                    if (now - heartbeatAt >= 15_000) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        heartbeatAt = now;
                    }
                    Thread.sleep(intervalMillis);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (SftpException exception) {
                log.info(
                        "SFTP SSE 因会话结束停止 userId={} sessionId={} code={} message={}",
                        owner,
                        sessionId,
                        exception.getCode(),
                        exception.getMessage()
                );
                // 会话被主动关闭或空闲回收，通知客户端停止自动重连旧 sessionId。
                try {
                    emitter.send(SseEmitter.event().name("session-closed")
                            .data(java.util.Map.of(
                                    "code", exception.getCode(),
                                    "message", exception.getMessage()
                            )));
                } catch (Exception ignored) {
                    // 客户端可能已经断开，此时无需再次发送关闭事件。
                }
            } catch (Exception exception) {
                // 文件任务不因 SSE 断线取消；客户端重连或 GET 快照恢复展示。
                log.warn(
                        "SFTP SSE 订阅异常 userId={} sessionId={} errorType={} message={}",
                        owner,
                        sessionId,
                        exception.getClass().getSimpleName(),
                        exception.getMessage()
                );
                emitter.complete();
            } finally {
                close();
            }
        }

        /** 幂等结束订阅、移除注册表记录并停止发布线程。 */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            subscriptions.remove(sessionId, this);
            if (Thread.currentThread() != thread) {
                thread.interrupt();
            }
            emitter.complete();
            log.info("SFTP SSE 订阅结束 userId={} sessionId={}", owner, sessionId);
        }
    }

    /** 应用关闭时终止全部 SSE 发布线程。 */
    @PreDestroy
    public void close() {
        List.copyOf(subscriptions.values()).forEach(Subscription::close);
    }
}
