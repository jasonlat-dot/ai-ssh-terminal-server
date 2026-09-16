package com.jasonlat.ai.domain.agent.service.context.reducer.impl;

import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.service.context.reducer.AbstractReducerSupport;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合裁剪器（ChatContextService 实际使用的裁剪策略）
 * <p>
 * 功能：组合两种裁剪器的优点——PriorityReducer 保证"重要的不丢"，
 * SlidingWindowReducer 保证"新的不丢"，取两者保留结果的交集，
 * 再按 minimum-recent-messages 强制保底最近 N 条。交集策略比任一单策略更严格，
 * 最终结果一定同时满足两种约束。
 * <p>
 * 运行过程：
 * <pre>
 *   messages ──+--> PriorityReducer.reduce()  --> 保留集合 A（按重要性）
 *              |
 *              +--> SlidingWindowReducer.reduce() --> 保留集合 B（按时效）
 *                          |
 *                          v
 *                  keep = A ∩ B（交集，双重约束）
 *                          |
 *                          v
 *                  保底：强制加入配置指定的最近 N 条索引
 *                          |
 *                          v
 *                  按原顺序输出保留的消息
 * </pre>
 * 注：indexSet 通过 all.indexOf(msg) 把消息对象映射回原列表下标，
 * 依赖 Map 的 equals 比较内容，内容相同的消息可能定位到首个匹配项，
 * 对裁剪结果无实质影响（内容相同裁谁都一样）。
 */
@Component
public class HybridReducer extends AbstractReducerSupport {

    @Resource 
    private PriorityReducer priorityReducer;
    
    @Resource 
    private SlidingWindowReducer slidingReducer;

    @Resource
    private AgentContextProperties contextProperties;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        Set<Integer> priorityKeep = indexSet(priorityReducer.reduce(messages, tokenBudget), messages);
        Set<Integer> slidingKeep  = indexSet(slidingReducer.reduce(messages, tokenBudget), messages);

        // 取交集
        Set<Integer> keepIndices = new HashSet<>(priorityKeep);
        keepIndices.retainAll(slidingKeep);

        // 保证至少保留配置指定的最近 N 条。
        int configuredMinimum = Math.max(
                0,
                contextProperties.getReducer().getMinimumRecentMessages()
        );
        int minKeep = Math.min(configuredMinimum, messages.size());
        for (int i = messages.size() - minKeep; i < messages.size(); i++) {
            keepIndices.add(i);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) result.add(messages.get(i));
        }
        return result;
    }

    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new HashSet<>();
        for (Map<String, Object> msg : subset) {
            int idx = all.indexOf(msg);
            if (idx >= 0) indices.add(idx);
        }
        return indices;
    }

}
