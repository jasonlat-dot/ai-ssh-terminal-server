package com.jasonlat.ai.cases.react.model;

import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;

import java.util.concurrent.Future;

/** SSE 生命周期与后台执行任务之间的取消桥接。 */
public final class ReActStreamCancellation {

    private ReActStreamCancellation() {
    }

    public static boolean cancel(
            DefaultReActFactory.DynamicContext context,
            Future<?> task) {
        if (context == null || context.getCompleted().get()) {
            return false;
        }
        context.getCancelled().set(true);
        return task != null && task.cancel(true);
    }
}
