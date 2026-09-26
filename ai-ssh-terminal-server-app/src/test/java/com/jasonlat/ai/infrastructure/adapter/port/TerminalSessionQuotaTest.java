package com.jasonlat.ai.infrastructure.adapter.port;

import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.infrastructure.model.settings.TerminalSessionSettings;
import com.jasonlat.ai.types.exception.AppException;
import com.jcraft.jsch.ChannelShell;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TerminalSessionQuotaTest {

    @Test
    public void shouldEnforceUserConnectionAndTotalLimitsAtomically() throws Exception {
        TerminalSessionSettings properties = properties(4, 2, 1);
        TestTerminalSessionSupport support = new TestTerminalSessionSupport(properties);

        support.reserve("user-1", "connection-1");
        assertLimitExceeded(() -> support.reserve("user-2", "connection-1"));

        support.reserve("user-1", "connection-2");
        assertLimitExceeded(() -> support.reserve("user-1", "connection-3"));

        support.reserve("user-2", "connection-3");
        support.reserve("user-3", "connection-4");
        assertLimitExceeded(() -> support.reserve("user-4", "connection-5"));

        support.release("user-1", "connection-1");
        support.reserve("user-4", "connection-5");
    }

    @Test
    public void concurrentReservationsMustNotExceedGlobalLimit() throws Exception {
        int limit = 10;
        int attempts = 40;
        TestTerminalSessionSupport support = new TestTerminalSessionSupport(properties(limit, limit, 1));
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            int index = i;
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    support.reserve("user-" + index, "connection-" + index);
                    accepted.incrementAndGet();
                } catch (AppException ignored) {
                    // 超出配额是本测试期望的结果。
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        Assert.assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();
        Assert.assertEquals(limit, accepted.get());
    }

    @Test
    public void idleSessionMustWaitForLongPollToFinishBeforeCleanup() throws Exception {
        TerminalSessionSettings properties = new TerminalSessionSettings(10, 10, 10, 1, 5, 5, 1000);
        TerminalSessionPort port = new TerminalSessionPort(Mockito.mock(SshSessionPort.class), properties);
        TerminalSessionPortSupport.TerminalSessionContext context = connectedContext("session-idle");
        context.lastActiveAtMillis.set(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2));
        context.pendingRead = new CompletableFuture<>();
        port.reserveSessionQuota(context.userId, context.connectionId);
        port.terminalSessions.put(context.sessionId, context);

        Assert.assertTrue(port.cleanupInactiveSessions().isEmpty());
        Assert.assertTrue(port.terminalSessions.containsKey(context.sessionId));

        context.pendingRead.complete(null);
        Assert.assertEquals(List.of(context.sessionId), port.cleanupInactiveSessions());
        Assert.assertFalse(port.terminalSessions.containsKey(context.sessionId));
    }

    @Test
    public void disconnectedSessionMustBeCleanedWithoutWaitingForIdleTimeout() throws Exception {
        TerminalSessionSettings properties = properties(10, 10, 10);
        TerminalSessionPort port = new TerminalSessionPort(Mockito.mock(SshSessionPort.class), properties);
        TerminalSessionPortSupport.TerminalSessionContext context = context("session-disconnected", false);
        CompletableFuture<TerminalReadResult> pendingRead = new CompletableFuture<>();
        context.pendingRead = pendingRead;
        port.reserveSessionQuota(context.userId, context.connectionId);
        port.terminalSessions.put(context.sessionId, context);

        Assert.assertEquals(List.of(context.sessionId), port.cleanupInactiveSessions());
        Assert.assertTrue(pendingRead.isDone());
    }

    private TerminalSessionSettings properties(int total, int perUser, int perConnection) throws Exception {
        return new TerminalSessionSettings(total, perUser, perConnection, 30, 5, 5, 1000);
    }

    private void assertLimitExceeded(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            Assert.fail("应拒绝超过终端配额的请求");
        } catch (AppException expected) {
            Assert.assertFalse(expected.getMessage().isBlank());
        }
    }

    private TerminalSessionPortSupport.TerminalSessionContext connectedContext(String sessionId) {
        return context(sessionId, true);
    }

    private TerminalSessionPortSupport.TerminalSessionContext context(String sessionId, boolean connected) {
        ChannelShell channel = Mockito.mock(ChannelShell.class);
        Mockito.when(channel.isConnected()).thenReturn(connected);
        Mockito.when(channel.isClosed()).thenReturn(!connected);
        TerminalSessionPortSupport.TerminalSessionContext context =
                new TerminalSessionPortSupport.TerminalSessionContext(
                        sessionId,
                        "user-test",
                        "connection-" + sessionId,
                        channel,
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream()
                );
        context.readerThread = Thread.currentThread();
        context.readerRunning.set(true);
        return context;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class TestTerminalSessionSupport extends TerminalSessionPortSupport {
        private TestTerminalSessionSupport(TerminalSessionSettings properties) {
            super(properties);
        }

        private void reserve(String userId, String connectionId) {
            reserveSessionQuota(userId, connectionId);
        }

        private void release(String userId, String connectionId) {
            releaseSessionQuota(userId, connectionId);
        }
    }
}
