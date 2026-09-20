package com.jasonlat.ai.domain.agent.service.intent.classifier.cache;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author jasonlat
 * 2026-09-19  20:56
 */
@Component
public class ClassifyCache {

    public CacheEntry newCacheEntry(IntentResultVO result, long expireTime) {
        return new CacheEntry(result, expireTime);
    }

    // 简单的 LRU 缓存，最大 128 个条目
    public final Map<String, ClassifyCache.CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ClassifyCache.CacheEntry> eldest) {
                    return size() > 128;
                }
            });

    @Getter
    public static class CacheEntry {
        IntentResultVO result;
        long expireTime;

        private CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }


    public IntentResultVO get(String cacheKey) {
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.getExpireTime() > System.currentTimeMillis()) {
            return cached.getResult();
        }

        return null;
    }

    public void putResult(String cacheKey, IntentResultVO resultVO) {
        // 缓存5分钟
        cache.put(cacheKey, newCacheEntry(resultVO, System.currentTimeMillis() + 5 * 60 * 1000 ));
    }


    public String cacheKey(String sessionId, String userMessage) {
        return sessionId + ":" + hashMessage(userMessage);
    }

    /**
     * 将消息转为缓存键用的哈希值（十六进制字符串）。
     * <p>
     * 使用 {@link String#hashCode()} 的十六进制表示，非加密哈希，
     * 仅用于缓存键拼接，不与消息原文一一对应。
     *
     * @param message 用户消息
     * @return 十六进制哈希字符串
     */
    private String hashMessage(String message) {
        return Integer.toHexString(message.hashCode());
    }
}
