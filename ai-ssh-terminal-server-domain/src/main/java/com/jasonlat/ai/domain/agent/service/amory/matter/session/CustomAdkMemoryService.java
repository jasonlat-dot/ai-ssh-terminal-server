package com.jasonlat.ai.domain.agent.service.amory.matter.session;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.common.collect.ImmutableList;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;

/**
 * ADK 长期 Memory 的空实现。
 *
 * <p>当前项目将跨 HTTP 请求的对话历史、命令摘要、里程碑和工具摘要统一交给业务层
 * ConversationContextStore 管理，并在每次调用前通过动态 Prompt 和 CustomAdkSessionService
 * 投影给 ADK。如果同时启用 ADK Memory，历史可能被第二次检索和注入，形成双重上下文来源。</p>
 *
 * <p>因此该实现只用于满足 Runner 对 {@link BaseMemoryService} 的依赖：不写入 Session，
 * 搜索始终返回空集合。以后接入向量记忆时，应先明确它只保存哪类长期知识，避免重新保存
 * ConversationContextStore 已管理的原始对话。</p>
 */
@Component
public class CustomAdkMemoryService implements BaseMemoryService {

    /**
     * 有意不把本次 ADK Session 写入长期 Memory。
     * 返回已完成的 Completable，表示“不存储”是正常策略而不是执行失败。
     */
    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.complete();
    }

    /**
     * 当前未启用长期记忆检索，因此任何查询都返回合法的空响应。
     */
    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.just(SearchMemoryResponse.builder()
                .memories(ImmutableList.of())
                .build());
    }
}
