package com.jasonlat.ai.domain.agent.model.valobj.dynamic;

import io.reactivex.rxjava3.disposables.Disposable;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次对话独有的取消信号。父 Runner、派发线程与 SSH 工具共享同一个实例，
 * 不按 sessionId 全局复用，避免停止上一轮时误伤同一会话的新请求。
 */
public final class AgentRunCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** 同一线程可能嵌套进入派发工具和 SSH 工具，需按进入次数登记。 */
    private final ConcurrentHashMap<Thread, AtomicInteger> threads = new ConcurrentHashMap<>();
    private final Set<Disposable> subscriptions = ConcurrentHashMap.newKeySet();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("对话已停止");
        }
    }

    public void registerCurrentThread() {
        Thread thread = Thread.currentThread();
        threads.compute(thread, (ignored, count) -> {
            if (count == null) return new AtomicInteger(1);
            count.incrementAndGet();
            return count;
        });
        throwIfCancelled();
    }

    public void unregisterCurrentThread() {
        threads.computeIfPresent(Thread.currentThread(), (ignored, count) ->
                count.decrementAndGet() == 0 ? null : count);
    }

    public void registerSubscription(Disposable subscription) {
        subscriptions.add(subscription);
        if (isCancelled()) subscription.dispose();
    }

    public void unregisterSubscription(Disposable subscription) {
        if (subscription != null) subscriptions.remove(subscription);
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        for (Disposable subscription : subscriptions) subscription.dispose();
        for (Thread thread : threads.keySet()) thread.interrupt();
    }
}
