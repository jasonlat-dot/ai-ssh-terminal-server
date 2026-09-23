package com.jasonlat.ai.test.domain.agent;

import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunCancellationTest {

    @Test
    void cancelsRegisteredChildThreadWithoutAffectingAnotherRequest() throws Exception {
        AgentRunCancellation stoppedRun = new AgentRunCancellation();
        AgentRunCancellation nextRun = new AgentRunCancellation();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread child = new Thread(() -> {
            try {
                stoppedRun.registerCurrentThread();
                started.countDown();
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } finally {
                stoppedRun.unregisterCurrentThread();
            }
        });
        child.setDaemon(true);
        child.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        stoppedRun.cancel();
        child.join(2_000);

        assertFalse(child.isAlive());
        assertTrue(interrupted.get());
        assertTrue(stoppedRun.isCancelled());
        assertFalse(nextRun.isCancelled());
    }
}
