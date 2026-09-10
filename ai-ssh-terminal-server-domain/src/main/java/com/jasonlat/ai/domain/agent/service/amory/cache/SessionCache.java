package com.jasonlat.ai.domain.agent.service.amory.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.adk.sessions.BaseSessionService;
import com.jasonlat.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.jasonlat.ai.domain.agent.service.amory.cache.model.SessionData;
import com.jasonlat.ai.domain.agent.service.amory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class SessionCache {

    @Resource
    private  DefaultArmoryFactory armoryFactory;

    private static final Logger log = LoggerFactory.getLogger(SessionCache.class);
    // 核心：本地缓存，支持每个key自定义过期
    private final Cache<String, SessionData> sessionCache;

    public SessionCache() {
        sessionCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfter(new Expiry<String, SessionData>() {
                    @Override
                    public long expireAfterCreate(String key, SessionData value, long currentTime) {
                        // 返回纳秒数，从创建开始计算
                        return TimeUnit.SECONDS.toNanos(value.getExpireSeconds());
                    }
                    @Override
                    public long expireAfterUpdate(String key, SessionData value, long currentTime, long currentDuration) {
                        // 更新时重新计算
                        return TimeUnit.SECONDS.toNanos(value.getExpireSeconds());
                    }
                    @Override
                    public long expireAfterRead(String key, SessionData value, long currentTime, long currentDuration) {
                        // 读取时重新计算
                        return TimeUnit.SECONDS.toNanos(value.getExpireSeconds());
                    }

                })
                .evictionListener((key, value, cause) -> {
                    // 监听缓存的删除
                    if (RemovalCause.EXPIRED == cause) {
                        log.info("session 过期, key: {}, value: {}", key, value);
                        // 清理谷歌ADK的 session
                        if (value == null) return;
                        armoryFactory.deleteAdkSession(value.getAgentId(), value.getUserId(), value.getSessionId());
                    }
                })
                .build();
    }

    // 存入并指定过期时间
    public void put(String agentId, String userId, String sessionId, long expireSeconds) {
        SessionData data = new SessionData(userId, sessionId, agentId, expireSeconds);
        String key = userId + "_" + agentId + "_" + sessionId;
        sessionCache.put(key, data);
    }

    // 获取
    public String get(String userId, String sessionId, String agentId) {
        String key = userId + "_" + agentId + "_" + sessionId;
        SessionData data = sessionCache.getIfPresent(key);
        return data == null ? null : data.getSessionId();
    }

    public void invalidate(String agentId, String userId, String sessionId) {
        String key = userId + "_" + agentId + "_" + sessionId;
        sessionCache.invalidate(key);
        // 删除 谷歌ADK session 缓存
        armoryFactory.deleteAdkSession(agentId, userId, sessionId);
    }

}