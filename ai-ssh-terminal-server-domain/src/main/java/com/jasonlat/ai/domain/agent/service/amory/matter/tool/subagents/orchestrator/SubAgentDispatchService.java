package com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.orchestrator;


import com.google.adk.agents.RunConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.TaskStatus;
import com.jasonlat.ai.domain.agent.service.amory.createlog.LlmSubAgentCatalog;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.factory.CustomRunnerFactory;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.AdkToolProvider;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.subagents.SubAgentDispatchTool;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 子 Agent 派发服务 - 动态编排体系中实际执行单个任务的 Spring 服务。
 * <p>
 * 与 {@link SubAgentDispatchTool}（包装固定子 Agent 给 LLM 调用）不同，
 * 本服务面向运行期规划出的任务列表：从 AgentCatalog 查找任务指定的子 Agent，
 * 透传父会话绑定的 SSH 终端会话（ThreadLocal），带超时执行并回写任务状态。
 * 编排器（DynamicAgentOrchestrator）通过本服务完成每个 DynamicTask 的执行。
 */
@Slf4j
@Service
public class SubAgentDispatchService {

    private final LlmSubAgentCatalog agentCatalog;
    private final CustomRunnerFactory runnerFactory;
    private final Executor executor;

    /**
     * 事件发布器负责把独立 Runner 的事件转发到父请求的 SSE 流。
     */
    private final AgentEventPublisher agentEventPublisher;

    /**
     * 兼容旧调用方式；使用独立发布器兜底，但不会连接任何 SSE 监听器。
     */
    public SubAgentDispatchService(LlmSubAgentCatalog agentCatalog,
                                   CustomRunnerFactory runnerFactory,
                                   Executor executor) {
        this(agentCatalog, runnerFactory, executor, new AgentEventPublisher());
    }

    /**
     * Spring 注入入口；显式限定线程池，避免与框架默认异步执行器混淆。
     */
    @Autowired
    public SubAgentDispatchService(LlmSubAgentCatalog agentCatalog,
                                   CustomRunnerFactory runnerFactory,
                                   @Qualifier("threadPoolExecutor") Executor executor,
                                   AgentEventPublisher agentEventPublisher) {
        this.agentCatalog = agentCatalog;
        this.runnerFactory = runnerFactory;
        this.executor = executor;
        this.agentEventPublisher = agentEventPublisher;
    }

    /**
     * 重试退避间隔上限（秒），指数退避 2^n 到此封顶
     */
    private static final long MAX_BACKOFF_SECONDS = 30;

    /**
     * 并发派发一批任务（无依赖编排的简单批量场景）。
     * 每个任务提交到线程池执行，全部完成后汇总：tasks（含状态/结果）与 allSucceeded。
     */
    public Map<String, Object> dispatch(AgentExecutionContext context, List<DynamicTask> tasks) {
        Map<String, Object> response = new LinkedHashMap<>();
        Semaphore concurrency = new Semaphore(Math.max(1, tasks.size()));

        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> {
                    try {
                        concurrency.acquire();
                        execute(context, task);
                    } catch (Exception exception) {
                        task.setStatus(TaskStatus.FAILED);
                        task.setError(exception.getMessage());
                    } finally {
                        concurrency.release();
                    }
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        response.put("tasks", tasks);
        response.put("allSucceeded", tasks.stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED));
        return response;
    }

    /**
     * 执行单个任务并原地更新任务状态（含失败重试）：
     * <ol>
     *   <li>置为 RUNNING，从 AgentCatalog 查找子 Agent（先按父 Agent 精确匹配，再全局兜底），找不到直接置 FAILED（确定性错误，不重试）</li>
     *   <li>按 maxRetries（默认 0）循环尝试：每次以独立会话运行子 Agent，按任务超时时间（默认 120 秒）阻塞等待</li>
     *   <li>成功则回写 result 并置 COMPLETED；执行期异常按 2^n 秒指数退避（上限 30 秒）后重试，重试耗尽置 FAILED</li>
     *   <li>回写 attempts（实际尝试次数），finally 中清理 ThreadLocal，避免线程池复用导致的会话串扰</li>
     * </ol>
     */
    public void execute(AgentExecutionContext context, DynamicTask task) {
        task.setStatus(TaskStatus.RUNNING);
        String invocationId = UUID.randomUUID().toString();
        BaseAgent agent = agentCatalog.find(context.getAgentId(), task.getAgentName())
                .or(() -> agentCatalog.findByName(task.getAgentName()))
                .orElse(null);

        if (agent == null) {
            task.setStatus(TaskStatus.FAILED);
            task.setError("agent not found: " + task.getAgentName());
            return;
        }

        // 最大尝试次数 = 首次执行 + 重试次数；maxRetries 做下限保护，避免 LLM 传负数
        int maxAttempts = 1 + Math.max(0, task.getMaxRetries() == null ? 0 : task.getMaxRetries());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            task.setAttempts(attempt);
            try {
                String terminalSessionId = context.getTerminalSessionId();
                if (terminalSessionId == null || terminalSessionId.isBlank()) {
                    throw new IllegalStateException("父 Agent 未绑定 SSH 终端会话");
                }

                Runner runner = runnerFactory.create(agent, agent.name(), List.of());
                String userId = "subAgent-user-" + context.getUserId();
                String childSessionId = "subAgent-" + invocationId + "-" + attempt;

                Map<String, Object> initialState = new ConcurrentHashMap<>();
                initialState.put(AdkToolProvider.TERMINAL_SESSION_STATE_KEY, terminalSessionId);

                Content content = Content.fromParts(Part.fromText(task.getRequest()));
                RunConfig runConfig = RunConfig.builder()
                        .autoCreateSession(false)
                        .build();

                List<Event> events = new ArrayList<>();
                Single.defer(() ->
                                // 先用父请求的终端 ID 创建子 Session，再启动子 Agent。
                                runner.sessionService().createSession(
                                        runner.appName(), userId, initialState, childSessionId))
                        .flatMapPublisher(session -> {
                            log.info("子Agent Session 创建成功 | sessionKey:{} | terminalSessionId:{}",
                                    session.sessionKey(), terminalSessionId);
                            return runner.runAsync(userId, childSessionId, content, runConfig);
                        })
                        .timeout(Optional.ofNullable(task.getTimeoutSeconds()).orElse(120), TimeUnit.SECONDS)
                        .blockingForEach(event -> {
                            // 边执行边发布，前端可以实时看到子 Agent 的工具调用，而不是等待任务汇总后一次性返回（否则ui渲染效果就不咋地了，一坨一坨的）。
                            events.add(event);
                            if (context.getParentSessionId() != null
                                    && !context.getParentSessionId().isBlank()
                                    && !"unknown".equals(context.getParentSessionId())) {
                                agentEventPublisher.publish(
                                        context.getParentSessionId(), context.getParentSessionKey(), event, true);
                            } else {
                                agentEventPublisher.publishToOnlyActiveSession(event, true);
                            }
                        });

                task.setResult(toResult(events));
                task.setStatus(TaskStatus.COMPLETED);
                return;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 5));
                    log.warn("子Agent执行失败，{}秒后重试 | agent={} | task={} | attempt={}/{} | error={}",
                            backoff, task.getAgentName(), task.getTaskId(), attempt, maxAttempts, e.getMessage());
                    sleepQuietly(backoff);
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("attempts=" + attempt + " | " + e.getMessage());
                    log.error("子Agent执行失败（重试耗尽） | agent={} | task={} | attempts={}", task.getAgentName(), task.getTaskId(), attempt, e);
                }
            }
        }

    }

    /**
     * 退避等待：被中断时恢复中断标记并直接返回（任务让位给外层超时/取消语义）
     */
    private void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 拼接事件中全部文本片段作为任务执行结果（取空时返回空串）
     */
    private String toResult(List<Event> events) {
        return events.isEmpty() ? "" : events.get(events.size() - 1).content()
                .flatMap(Content::parts)
                .flatMap(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce((left, right) -> left + right))
                .orElse("");
    }

}
