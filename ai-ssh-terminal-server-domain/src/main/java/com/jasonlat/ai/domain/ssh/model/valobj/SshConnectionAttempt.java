package com.jasonlat.ai.domain.ssh.model.valobj;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** 一次建连请求的取消上下文；资源回调只属于本次请求，不包含复用的 SSH Session。 */
@Slf4j
public final class SshConnectionAttempt {
    private static final ThreadLocal<SshConnectionAttempt> CURRENT = new ThreadLocal<>();
    private final List<Runnable> cancellationHandlers = new ArrayList<>();
    private boolean cancelled;

    public static SshConnectionAttempt current() {
        return CURRENT.get();
    }

    public static void checkCurrent() {
        SshConnectionAttempt attempt = current();
        if (attempt != null) attempt.checkCancelled();
    }

    public synchronized void checkCancelled() {
        if (cancelled) throw new CancellationException("SSH 连接已取消");
    }

    public void onCancel(Runnable handler) {
        synchronized (this) {
            if (!cancelled) {
                cancellationHandlers.add(handler);
                return;
            }
        }
        release(handler);
        checkCancelled();
    }

    public void cancel() {
        List<Runnable> handlers;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            handlers = new ArrayList<>(cancellationHandlers);
            cancellationHandlers.clear();
        }
        handlers.forEach(SshConnectionAttempt::release);
    }

    public <T> T execute(Supplier<T> action) {
        SshConnectionAttempt previous = CURRENT.get();
        CURRENT.set(this);
        try {
            checkCancelled();
            T result = action.get();
            synchronized (this) {
                checkCancelled();
                cancellationHandlers.clear();
            }
            return result;
        } finally {
            synchronized (this) { cancellationHandlers.clear(); }
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    private static void release(Runnable handler) {
        try { handler.run(); }
        catch (RuntimeException exception) { log.warn("释放已取消的 SSH 建连资源失败", exception); }
    }
}
