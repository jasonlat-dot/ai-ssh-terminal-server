package com.jasonlat.ai.cases.react;

import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.cases.react.facotry.DefaultReActFactory;
import com.jasonlat.ai.cases.react.model.ReActStreamCancellation;
import com.jasonlat.ai.cases.react.node.RootNode;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import com.jasonlat.ai.trigger.api.dto.ReActResultDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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

    /** 同一个会话串行执行，不同会话可以并发，避免业务历史相互覆盖。 */
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 流式对话（ReAct 模式）
     *
     * @param requestDTO 对话请求
     * @return SSE 事件发射器
     */
    @Override
    public ResponseBodyEmitter chatStream(ChatRequest requestDTO) {
        long requestStartNanos = System.nanoTime();
        // 1. 创建 SSE 发射器（30 分钟超时）
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(30 * 60 * 1000L);

        try {
            log.info("ReAct链路-请求接收 | mode:stream | sessionId:{} | userId:{} | agentId:{} | "
                            + "terminalSessionId:{} | messageLength:{} | sseTimeoutMinutes:{}",
                    requestDTO.getSessionId(), requestDTO.getUserId(), requestDTO.getAgentId(),
                    requestDTO.getTerminalSessionId(), safeLength(requestDTO.getMessage()), 30);
            log.debug("ReAct链路-创建 SSE emitter 完成 | sessionId:{} | emitterType:{}",
                    requestDTO.getSessionId(), emitter.getClass().getSimpleName());

            // 2. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
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
            emitter.onTimeout(cancelTask);
            emitter.onError(error -> cancelTask.run());
            emitter.onCompletion(cancelTask);
            log.debug("ReAct链路-SSE 回调注册完成 | sessionId:{}", requestDTO.getSessionId());

            Future<?> task = threadPoolExecutor.submit(
                    () -> executeStream(requestDTO, dynamicContext, emitter));
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
        long requestStartNanos = System.nanoTime();
        log.info("ReAct链路-请求接收 | mode:sync | sessionId:{} | userId:{} | agentId:{} | "
                        + "terminalSessionId:{} | messageLength:{}",
                requestDTO.getSessionId(), requestDTO.getUserId(), requestDTO.getAgentId(),
                requestDTO.getTerminalSessionId(), safeLength(requestDTO.getMessage()));

        try {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new ResponseBodyEmitter(60 * 1000L))
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            log.info("ReAct链路-同步请求完成 | sessionId:{} | stopReason:{} | steps:{} | "
                            + "toolCalls:{} | contentLength:{} | durationMs:{}",
                    requestDTO.getSessionId(), result.getStopReason(), result.getTotalSteps(),
                    result.getTotalToolCalls(), safeLength(result.getContent()), elapsedMillis(requestStartNanos));
            return result.getContent();

        } catch (Exception e) {
            log.error("ReAct链路-同步请求异常 | sessionId:{} | durationMs:{}",
                    requestDTO.getSessionId(), elapsedMillis(requestStartNanos), e);
            return "Error: " + e.getMessage();
        }
    }

    private void executeStream(ChatRequest requestDTO,
                               DefaultReActFactory.DynamicContext context,
                               ResponseBodyEmitter emitter) {
        String sessionId = requestDTO.getSessionId();
        long executionStartNanos = System.nanoTime();
        // saveExecutionState 采用整份会话状态回写，同一 session 必须串行，避免后完成的旧快照覆盖新历史。
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        long lockWaitStartNanos = System.nanoTime();
        try {
            log.debug("ReAct链路-等待会话锁 | sessionId:{} | locked:{} | queuedThreads:{}",
                    sessionId, lock.isLocked(), lock.getQueueLength());
            lock.lockInterruptibly();
            log.info("ReAct链路-获得会话锁 | sessionId:{} | waitMs:{}",
                    sessionId, elapsedMillis(lockWaitStartNanos));
            log.info("ReAct链路-进入 RootNode | sessionId:{}", sessionId);
            ReActResultDTO result = rootNode.apply(requestDTO, context);
            log.info("ReAct链路-流式请求完成 | sessionId:{} | steps:{} | toolCalls:{} | "
                            + "toolResults:{} | stopReason:{} | contentLength:{} | durationMs:{}",
                    sessionId, result.getTotalSteps(), result.getTotalToolCalls(),
                    result.getToolResults() == null ? 0 : result.getToolResults().size(),
                    result.getStopReason(), safeLength(result.getContent()), elapsedMillis(executionStartNanos));
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
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("ReAct链路-释放会话锁 | sessionId:{} | queuedThreads:{}",
                        sessionId, lock.getQueueLength());
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
