package com.jasonlat.ai.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.ReActStreamCancellation;
import com.jasonlat.ai.cases.react.node.RootNode;
import com.jasonlat.ai.cases.react.multimodal.ChatRequestContentSupport;
import com.jasonlat.ai.domain.agent.service.multimodal.ChatAttachmentService;
import com.jasonlat.ai.trigger.api.dto.ReActEventDTO;
import com.jasonlat.ai.types.exception.AppException;
import com.jasonlat.ai.domain.agent.service.events.AgentEventPublisher;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * AI 智能体 ReAct 执行服务实现
 *
 * <p>职责：
 * - 流式对话（SSE）：创建 emitter → 创建动态上下文 → 走节点链路
 * - 普通对话（非流式）：直接调用节点链路
 *
 * <p>节点链路：RootNode → AiCallNode →（可选 ToolCallNode）→
 * LoopDecisionNode → UserFeedbackNode。</p>
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private AgentEventPublisher agentEventPublisher;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ChatAttachmentService chatAttachmentService;

    /** 只保留正在执行或排队的会话锁，最后一个使用者离开后自动删除。 */
    private final ConcurrentHashMap<String, SessionLock> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<ActiveStream>> activeStreams = new ConcurrentHashMap<>();

    private static final class SessionLock {
        private final ReentrantLock lock = new ReentrantLock();
        /** 只在 sessionLocks.compute 中读写，包括持锁线程和排队线程。 */
        private int references;
    }

    private SessionLock retainSessionLock(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return sessionLocks.compute(sessionId, (ignored, current) -> {
            SessionLock retained = current == null ? new SessionLock() : current;
            retained.references++;
            return retained;
        });
    }

    private void releaseSessionLock(String sessionId, SessionLock retained) {
        sessionLocks.computeIfPresent(sessionId, (ignored, current) -> {
            if (current != retained) {
                return current;
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private record ActiveStream(String agentId, String userId, Runnable cancel, ResponseBodyEmitter emitter) {
    }

    @Override
    public boolean stopChat(String agentId, String userId, String sessionId) {
        if (agentId == null || userId == null || sessionId == null) return false;
        Set<ActiveStream> streams = activeStreams.get(sessionId);
        if (streams == null) return false;
        boolean stopped = false;
        for (ActiveStream stream : streams) {
            if (!stream.agentId().equals(agentId) || !stream.userId().equals(userId)) continue;
            stream.cancel().run();
            try {
                stream.emitter().complete();
            } catch (IllegalStateException ignored) {
                log.debug("停止对话时 SSE 已关闭 | sessionId:{}", sessionId);
            }
            stopped = true;
        }
        return stopped;
    }

    private void unregisterStream(String sessionId, ActiveStream stream) {
        activeStreams.computeIfPresent(sessionId, (ignored, streams) -> {
            streams.remove(stream);
            return streams.isEmpty() ? null : streams;
        });
    }

    /**
     * 流式对话（ReAct 模式）
     *
     * @param requestDTO 对话请求
     * @return SSE 事件发射器
     */
    @Override
    public ResponseBodyEmitter chatStream(ChatRequest requestDTO) {
        ChatRequestContentSupport.validateAndNormalize(requestDTO);
        long requestStartNanos = System.nanoTime();
        // 1. 创建 SSE 发射器（30 分钟超时）
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(30 * 60 * 1000L);
        AtomicReference<ActiveStream> activeStreamRef = new AtomicReference<>();

        try {
            log.info("ReAct链路-请求接收 | mode:stream | sessionId:{} | userId:{} | agentId:{} | "
                            + "terminalSessionId:{} | messageLength:{} | sseTimeoutMinutes:{}",
                    requestDTO.getSessionId(), requestDTO.getUserId(), requestDTO.getAgentId(),
                    requestDTO.getTerminalSessionId(), safeLength(requestDTO.getMessage()), 30);
            log.debug("ReAct链路-创建 SSE emitter 完成 | sessionId:{} | emitterType:{}",
                    requestDTO.getSessionId(), emitter.getClass().getSimpleName());

            // 2. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .streamingMode(RunConfig.StreamingMode.SSE)
                    .emitter(emitter)
                    .build();
            log.debug("ReAct链路-请求上下文创建完成 | sessionId:{} | completed:{} | cancelled:{}",
                    requestDTO.getSessionId(), dynamicContext.getCompleted().get(),
                    dynamicContext.getCancelled().get());

            /*
             * emitter 生命周期早于后台 Future：回调可能在 taskRef 赋值前触发。
             * 因此先写 cancelled 标记，拿到 Future 后再补一次 cancel，覆盖这个竞态窗口。
             */
            AtomicReference<Future<?>> taskRef = new AtomicReference<>();
            Runnable cancelTask = () -> {
                if (dynamicContext.getCompleted().get()) {
                    return;
                }
                Future<?> task = taskRef.get();
                boolean taskCancelled = ReActStreamCancellation.cancel(dynamicContext, task);
                log.info("ReAct链路-SSE 生命周期触发取消 | sessionId:{} | futureBound:{} | "
                                + "taskCancelled:{} | completed:{}",
                        requestDTO.getSessionId(), task != null, taskCancelled,
                        dynamicContext.getCompleted().get());
            };
            ActiveStream activeStream = new ActiveStream(requestDTO.getAgentId(), requestDTO.getUserId(), cancelTask, emitter);
            activeStreamRef.set(activeStream);
            activeStreams.compute(requestDTO.getSessionId(), (ignored, streams) -> {
                Set<ActiveStream> registered = streams == null ? ConcurrentHashMap.newKeySet() : streams;
                registered.add(activeStream);
                return registered;
            });
            Runnable unregister = () -> unregisterStream(requestDTO.getSessionId(), activeStream);
            emitter.onTimeout(() -> { cancelTask.run(); unregister.run(); });
            emitter.onError(error -> { cancelTask.run(); unregister.run(); });
            emitter.onCompletion(() -> { cancelTask.run(); unregister.run(); });
            log.debug("ReAct链路-SSE 回调注册完成 | sessionId:{}", requestDTO.getSessionId());

            Future<?> task = threadPoolExecutor.submit(
                    () -> executeStream(requestDTO, dynamicContext, emitter, activeStream));
            taskRef.set(task);
            log.info("ReAct链路-后台任务已提交 | sessionId:{} | activeThreads:{} | queuedTasks:{} | "
                            + "submitDurationMs:{}",
                    requestDTO.getSessionId(), threadPoolExecutor.getActiveCount(),
                    threadPoolExecutor.getQueue().size(), elapsedMillis(requestStartNanos));
            if (dynamicContext.getCancelled().get()) {
                boolean taskCancelled = task.cancel(true);
                log.info("ReAct链路-提交后检测到提前断连 | sessionId:{} | taskCancelled:{}",
                        requestDTO.getSessionId(), taskCancelled);
            }

        } catch (Exception e) {
            if (activeStreamRef.get() != null) unregisterStream(requestDTO.getSessionId(), activeStreamRef.get());
            log.error("ReAct链路-流式请求初始化失败 | sessionId:{} | durationMs:{}",
                    requestDTO.getSessionId(), elapsedMillis(requestStartNanos), e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 普通对话（单轮，非流式）
     *
     * @param requestDTO 对话请求
     * @return 对话响应内容
     */
    @Override
    public String chat(ChatRequest requestDTO) {
        ChatRequestContentSupport.validateAndNormalize(requestDTO);
        long requestStartNanos = System.nanoTime();
        log.info("ReAct链路-请求接收 | mode:sync | sessionId:{} | userId:{} | agentId:{} | "
                        + "terminalSessionId:{} | messageLength:{}",
                requestDTO.getSessionId(), requestDTO.getUserId(), requestDTO.getAgentId(),
                requestDTO.getTerminalSessionId(), safeLength(requestDTO.getMessage()));

        try {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .streamingMode(RunConfig.StreamingMode.SSE)
                    .emitter(new ResponseBodyEmitter(30 * 60 * 1000L))
                    .build();

            ReActResultDTO result = executeWithAttachmentPermit(requestDTO, dynamicContext);
            log.info("ReAct链路-同步请求完成 | sessionId:{} | stopReason:{} | steps:{} | "
                            + "toolCalls:{} | contentLength:{} | durationMs:{}",
                    requestDTO.getSessionId(), result.getStopReason(), result.getTotalSteps(),
                    result.getTotalToolCalls(), safeLength(result.getContent()), elapsedMillis(requestStartNanos));
            return result.getContent();

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("ReAct链路-同步请求异常 | sessionId:{} | durationMs:{}",
                    requestDTO.getSessionId(), elapsedMillis(requestStartNanos), e);
            return "Error: " + e.getMessage();
        }
    }

    /** 附件许可覆盖整个执行链；异常、取消和正常结束都会释放。 */
    private ReActResultDTO executeWithAttachmentPermit(ChatRequest request, DefaultReActFactory.DynamicContext context)
            throws Exception {
        try (ChatAttachmentService.Permit ignored = chatAttachmentService.acquire(ChatRequestContentSupport.hasAttachments(request))) {
            return rootNode.apply(request, context);
        }
    }

    private void executeStream(ChatRequest requestDTO,
                               DefaultReActFactory.DynamicContext context,
                               ResponseBodyEmitter emitter,
                               ActiveStream activeStream) {
        String sessionId = requestDTO.getSessionId();
        long executionStartNanos = System.nanoTime();
        // saveExecutionState 采用整份会话状态回写，同一 session 必须串行，避免后完成的旧快照覆盖新历史。
        SessionLock retainedLock = retainSessionLock(sessionId);
        ReentrantLock lock = retainedLock.lock;
        long lockWaitStartNanos = System.nanoTime();
        boolean listenerRegistered = false;
        try {
            log.debug("ReAct链路-等待会话锁 | sessionId:{} | locked:{} | queuedThreads:{}",
                    sessionId, lock.isLocked(), lock.getQueueLength());
            lock.lockInterruptibly();
            log.info("ReAct链路-获得会话锁 | sessionId:{} | waitMs:{}",
                    sessionId, elapsedMillis(lockWaitStartNanos));

            // /chat_stream 的 emitter 由 Case 创建；必须在 RootNode 启动前订阅当前业务会话，
            // 否则执行很快的子 Agent/SSH 工具可能在监听器建立前发出事件而被丢弃。
            // forwarder 按请求创建，它的调用去重、结果配对和文本累加状态不能跨会话共享。
            NestedAgentEventForwarder forwarder = new NestedAgentEventForwarder(objectMapper);
            agentEventPublisher.registerSession(sessionId, new Consumer<AgentEventPublisher.PublishedEvent>() {
                @Override
                public void accept(AgentEventPublisher.PublishedEvent event) {
                    // 就是lambda里面的执行逻辑
                    forwarder.forward(emitter, event);
                }
            });
            listenerRegistered = true;

            log.info("ReAct链路-进入 RootNode | sessionId:{}", sessionId);
            ReActResultDTO result = executeWithAttachmentPermit(requestDTO, context);

            log.info("ReAct链路-流式请求完成 | sessionId:{} | steps:{} | toolCalls:{} | "
                            + "toolResults:{} | stopReason:{} | contentLength:{} | durationMs:{}",
                    sessionId, result.getTotalSteps(), result.getTotalToolCalls(),
                    result.getToolResults() == null ? 0 : result.getToolResults().size(),
                    result.getStopReason(), safeLength(result.getContent()), elapsedMillis(executionStartNanos));
        } catch (AppException exception) {
            // 异步附件校验错误必须作为协议事件返回，不能只关闭连接让前端看到网络失败。
            try {
                ReActEventDTO event = new ReActEventDTO();
                event.setEvent("error");
                event.setCode(exception.getCode());
                event.setContent(exception.getInfo());
                emitter.send(objectMapper.writeValueAsString(event) + "\n");
                context.getCompleted().set(true);
                emitter.complete();
            } catch (Exception sendError) {
                emitter.completeWithError(sendError);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.info("ReAct链路-流式任务已中断 | sessionId:{} | cancelled:{} | durationMs:{}",
                    sessionId, context.getCancelled().get(), elapsedMillis(executionStartNanos));
        } catch (Exception exception) {
            log.error("ReAct链路-流式请求异常 | sessionId:{} | step:{} | toolCalls:{} | durationMs:{}",
                    sessionId, context.getStep(), context.getTotalToolCallCount().get(),
                    elapsedMillis(executionStartNanos), exception);
            try {
                emitter.completeWithError(exception);
            } catch (Exception ignored) {
                log.debug("ReAct链路-SSE 已关闭，忽略重复异常完成 | sessionId:{}", sessionId);
            }
        } finally {
            try {
                unregisterStream(sessionId, activeStream);
                if (listenerRegistered) {
                    agentEventPublisher.unregisterSession(sessionId);
                }
            } finally {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.debug("ReAct链路-释放会话锁 | sessionId:{} | queuedThreads:{}",
                                sessionId, lock.getQueueLength());
                    }
                } finally {
                    /* 排队期间被中断的线程也必须释放引用；最后一个引用负责从 Map 删除锁。 */
                    releaseSessionLock(sessionId, retainedLock);
                }
            }
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
