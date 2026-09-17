package com.jasonlat.ai.test.domain.agent;

import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.ReActStreamCancellation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActStreamCancellationTest {

    @Test
    void shouldCancelBackgroundTaskWhenClientDisconnects() {
        DefaultReActFactory.DynamicContext context =
                DefaultReActFactory.DynamicContext.builder().build();
        FutureTask<Void> task = new FutureTask<>(() -> null);

        assertTrue(ReActStreamCancellation.cancel(context, task));
        assertTrue(context.getCancelled().get());
        assertTrue(task.isCancelled());
    }

    @Test
    void shouldNotMarkNormallyCompletedStreamAsDisconnected() {
        DefaultReActFactory.DynamicContext context =
                DefaultReActFactory.DynamicContext.builder().build();
        context.getCompleted().set(true);
        FutureTask<Void> task = new FutureTask<>(() -> null);

        assertFalse(ReActStreamCancellation.cancel(context, task));
        assertFalse(context.getCancelled().get());
        assertFalse(task.isCancelled());
    }
}
