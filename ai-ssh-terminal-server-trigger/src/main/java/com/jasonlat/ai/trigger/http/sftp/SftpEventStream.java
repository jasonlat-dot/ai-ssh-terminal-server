package com.jasonlat.ai.trigger.http.sftp;

import com.jasonlat.ai.cases.sftp.SftpServiceCase;
import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpModels.Progress;
import jakarta.annotation.PreDestroy;
import com.jasonlat.ai.domain.sftp.model.valobj.SftpPolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个窗口只保留一个 SSE 订阅；断线重连发送最新快照，不重启文件任务。 */
@Component
public class SftpEventStream {
    private final SftpServiceCase service;
    private final long intervalMillis;
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    public SftpEventStream(SftpServiceCase service, SftpPolicy policy) {
        this.service = service;
        this.intervalMillis = policy.progressPublishInterval().toMillis();
    }
    public SseEmitter open(String owner, String sessionId) {
        service.session(owner, sessionId);
        Subscription next = new Subscription(owner, sessionId);
        Subscription previous = subscriptions.put(sessionId, next);
        if (previous != null) previous.close();
        next.thread.start();
        return next.emitter;
    }
    private final class Subscription {
        final String owner;
        final String sessionId;
        final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread thread;
        Subscription(String owner, String sessionId) {
            this.owner = owner; this.sessionId = sessionId;
            this.thread = Thread.ofVirtual().name("sftp-progress-" + sessionId).unstarted(this::run);
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(error -> close());
        }
        void run() {
            List<Progress> previous = null;
            long heartbeatAt = 0;
            try {
                while (!closed.get()) {
                    List<Progress> snapshot = service.progress(owner, sessionId).stream()
                            .sorted(java.util.Comparator.comparing(Progress::transferId)).toList();
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
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (SftpException e) {
                // 会话被主动关闭或空闲回收，通知客户端停止自动重连旧 sessionId。
                try {
                    emitter.send(SseEmitter.event().name("session-closed")
                            .data(java.util.Map.of("code", e.getCode(), "message", e.getMessage())));
                } catch (Exception ignored) { }
            }
            catch (Exception e) {
                // 文件任务不因 SSE 断线取消；客户端重连或 GET 快照恢复展示。
                emitter.complete();
            } finally { close(); }
        }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            subscriptions.remove(sessionId, this);
            if (Thread.currentThread() != thread) thread.interrupt();
            emitter.complete();
        }
    }
    @PreDestroy public void close() { List.copyOf(subscriptions.values()).forEach(Subscription::close); }
}
