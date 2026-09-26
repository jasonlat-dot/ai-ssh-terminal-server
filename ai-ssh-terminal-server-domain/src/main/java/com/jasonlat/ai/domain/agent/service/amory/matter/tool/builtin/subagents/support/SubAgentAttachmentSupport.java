package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.subagents.support;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.model.valobj.dynamic.AgentRunCancellation;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.AgentInvocationSupport;
import com.jasonlat.ai.domain.agent.service.multimodal.context.InvocationAttachmentScope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Runner 附件消息与临时 Session 生命周期。
 * 不读取 MinIO，不解析任务文字中的 fileId；
 * 只接收父请求传递的已授权附件上下文。
 */
public final class SubAgentAttachmentSupport {

    private SubAgentAttachmentSupport() {
    }

    public static InvocationAttachmentScope scopeFrom(ToolContext toolContext) {
        return AgentInvocationSupport.require(toolContext).attachmentScope();
    }

    /**
     * 每次订阅对应一次独立子任务执行。
     * 调用方负责传入唯一 childSessionId，例如每次重试使用新的 ID。
     */
    public static Flowable<Event> run(
            Runner runner,
            String targetAgentName,
            String userId,
            String childSessionId,
            String request,
            Map<String, Object> initialState,
            InvocationAttachmentScope scope,
            AgentRunCancellation cancellation,
            RunConfig runConfig) {

        return Flowable.defer(() -> {
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }

            // 能力不支持时明确失败，不静默降级成只有文件名的文字请求。
            Content content = scope == null
                    ? Content.fromParts(Part.fromText(request))
                    : scope.contentFor(targetAgentName, request);

            HashMap<String, Object> childState = new HashMap<>(initialState);

            SerialDisposable subscription = new SerialDisposable();

            if (scope != null) {
                scope.register(subscription);
            }
            if (cancellation != null) {
                cancellation.registerSubscription(subscription);
            }

            /*
             * using 管理临时 Session：
             * 正常完成、异常、超时导致的取消、父请求取消，都会执行释放。
             * eager=true：先清理资源，再向下游通知完成或错误。
             */
            return Flowable.using(
                    () -> {
                        if (subscription.isDisposed()) {
                            throw new java.util.concurrent.CancellationException(
                                    "子智能体任务已取消");
                        }
                        if (cancellation != null) {
                            cancellation.throwIfCancelled();
                        }

                        return runner.sessionService()
                                .createSession(
                                        runner.appName(),
                                        userId,
                                        childState,
                                        childSessionId)
                                .blockingGet();
                    },
                    session -> runner.runAsync(
                            userId, childSessionId, content, runConfig),
                    session -> runner.sessionService()
                            .deleteSession(runner.appName(), userId, childSessionId)
                            .blockingAwait(),
                    true)
                    .doOnSubscribe(upstream ->
                            subscription.set(Disposable.fromSubscription(upstream)))
                    .doFinally(() -> {
                        if (scope != null) {
                            scope.unregister(subscription);
                        }
                        if (cancellation != null) {
                            cancellation.unregisterSubscription(subscription);
                        }
                    });
        });
    }
}