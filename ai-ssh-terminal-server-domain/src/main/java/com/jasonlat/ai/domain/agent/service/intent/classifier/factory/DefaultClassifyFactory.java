package com.jasonlat.ai.domain.agent.service.intent.classifier.factory;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.*;
import org.springframework.stereotype.Component;

/**
 * @author jasonlat
 * 2026-09-19  19:01
 */
@Component
public class DefaultClassifyFactory {

    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        private String cacheKey;

        /** 是否连续失败次数过多 */
        private boolean failuresStatus;

        private IntentResultVO finalResult;
    }


}
