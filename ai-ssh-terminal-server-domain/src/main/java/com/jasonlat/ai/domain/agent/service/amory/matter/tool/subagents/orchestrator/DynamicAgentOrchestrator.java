package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.orchestrator;


import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 动态 Agent 编排器 - 按任务依赖关系（DAG）调度执行一批子 Agent 任务。
 * <p>
 * 调度策略：
 * <ol>
 *   <li>每轮从计划中筛选出"所有依赖任务均已 COMPLETED"的 PENDING 任务</li>
 *   <li>把这批就绪任务并发提交线程池，通过 Semaphore 限制并发上限（maxConcurrency）</li>
 *   <li>等待本轮全部结束后进入下一轮，直到没有 PENDING 任务（或死锁则提前退出）</li>
 * </ol>
 * 失败处理策略：
 * <ul>
 *   <li>单任务执行异常只置该任务为 FAILED（执行服务内部已按 maxRetries 重试），不中断整体</li>
 *   <li>依赖了 FAILED/SKIPPED 任务的下游 PENDING 任务被显式置为 SKIPPED，并沿依赖链级联传播</li>
 *   <li>计划开启 failFast 时，首个任务失败后不再调度新任务（进行中的跑完），剩余 PENDING 全部置 SKIPPED</li>
 * </ul>
 * 全部执行完成后返回汇总结果（计划、各任务状态、是否全部成功）。
 */
@Slf4j
@Service
public class DynamicAgentOrchestrator {

    private final SubAgentDispatchService dispatchService;
    private final Executor executor;

    /**
     * 显式注入业务线程池；ADK 子任务并发执行必须与 HTTP/框架默认线程池隔离。
     */
    public DynamicAgentOrchestrator(SubAgentDispatchService dispatchService, @Qualifier("threadPoolExecutor")ThreadPoolExecutor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    /**
     * 执行整个任务计划（阻塞直至全部任务到达终态）。
     *
     * @param context 子 Agent 执行上下文（携带终端会话等）
     * @param plan    经校验的任务计划
     * @return {plan, tasks(含状态/结果), allSucceeded}
     */
    public Map<String, Object> execute(AgentExecutionContext context, DynamicTaskPlan plan) {
        // taskId -> task 索引，便于依赖查询与状态更新
        Map<String, DynamicTask> tasks = new HashMap<>();
        plan.getTasks().forEach(task -> tasks.put(task.getTaskId(), task));

        // 并发额度：同一时刻最多 maxConcurrency 个任务在执行
        Semaphore concurrency = new Semaphore(Math.max(1, plan.getMaxConcurrency()));
        AgentRunCancellation cancellation = context.getCancellation();

        // 调度循环：每轮执行一批"依赖已全部满足"的就绪任务
        while (tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.PENDING)) {
            if (cancellation != null && cancellation.isCancelled()) {
                tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .forEach(task -> {
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setError("对话已停止");
                        });
                break;
            }
            // failFast：已有任务失败，不再调度新任务，剩余 PENDING 全部置 SKIPPED 后退出
            if (Boolean.TRUE.equals(plan.getFailFast())
                    && tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
                tasks.values().stream()
                        .filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .forEach(task -> {
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setError("skipped by failFast: another task failed");
                        });
                break;
            }

            // 级联跳过：依赖了 FAILED/SKIPPED 任务的 PENDING 任务显式置 SKIPPED（沿依赖链逐轮传播）
            tasks.values().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(tasks::get)
                            .anyMatch(parent -> parent == null
                                    || parent.getStatus() == TaskStatus.FAILED
                                    || parent.getStatus() == TaskStatus.SKIPPED))
                    .forEach(task -> {
                        task.setStatus(TaskStatus.SKIPPED);
                        task.setError("skipped: dependency failed or skipped");
                    });

            // 就绪任务：PENDING 且所有依赖任务均已 COMPLETED
            List<DynamicTask> readyTasks = tasks.values().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(tasks::get)
                            .allMatch(parent -> parent != null && parent.getStatus() == TaskStatus.COMPLETED))
                    .toList();

            // 无就绪任务：剩余 PENDING 任务依赖关系无法满足（校验器已排除环，此处兜底），提前退出
            if (readyTasks.isEmpty()) {
                break;
            }

            // 并发执行本轮就绪任务：信号量限流，异常任务置 FAILED 不中断整体
            List<CompletableFuture<Void>> futures = readyTasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {
                        boolean acquired = false;
                        try {
                            if (cancellation != null) cancellation.throwIfCancelled();
                            concurrency.acquire();
                            acquired = true;
                            if (cancellation != null) cancellation.throwIfCancelled();
                            dispatchService.execute(context, task);
                        } catch (Exception exception) {
                            task.setStatus(TaskStatus.FAILED);
                            task.setError(exception.getMessage());
                        } finally {
                            if (acquired) concurrency.release();
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        // 汇总返回：计划、各任务最终状态（含成功/失败/跳过数）、是否全部成功
        Map<String, Object> response = new HashMap<>();

        response.put("plan", plan);
        response.put("tasks", new ArrayList<>(tasks.values()));
        response.put("allSucceeded", tasks.values().stream().allMatch(task -> task.getStatus().equals(TaskStatus.COMPLETED)));
        response.put("failedCount", tasks.values().stream().filter(task -> task.getStatus().equals(TaskStatus.FAILED)).count());
        response.put("skippedCount", tasks.values().stream().filter(task -> task.getStatus().equals(TaskStatus.SKIPPED)).count());

        return response;
    }

}
