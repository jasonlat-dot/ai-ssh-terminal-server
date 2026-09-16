package com.jasonlat.ai.domain.agent.service.prompt.dynamic;

import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 里程碑追踪器，基于正则规则自动识别用户纠偏、任务完成、任务切换、工具报错等关键事件，并按会话维度缓存。
 */
@Slf4j
@Component
public class MilestoneTracker {

    private final ConversationContextStore conversationContextStore;
    private final AgentContextProperties.Milestone properties;
    private final List<CompiledMilestoneRule> rules;

    /**
     * 创建里程碑追踪器，并在应用启动时完成规则校验和正则预编译。
     * 配置错误会直接阻止应用启动，避免运行期间悄悄漏记里程碑。
     */
    public MilestoneTracker(
            ConversationContextStore conversationContextStore,
            AgentContextProperties contextProperties) {

        this.conversationContextStore = conversationContextStore;
        this.properties = contextProperties.getMilestone();
        this.rules = compileRules(this.properties.getRules());
    }

    /**
     * 检测并记录里程碑事件。
     * <p>
     * 规则来自 {@code ai.agent.context.milestone.rules}，先按 priority 降序匹配；
     * 相同优先级保持 YAML 中的声明顺序。一次消息只记录第一条命中的规则。
     *
     * @param sessionId 会话 ID，按会话隔离里程碑
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容，为 null 或空时直接返回
     */
    public void detectAndRecord(String sessionId, String role, String content) {
        if (!properties.isEnabled()
                || sessionId == null || sessionId.isBlank()
                || role == null || role.isBlank()
                || content == null || content.isBlank()) {
            return;
        }

        String normalizedRole = role.trim().toLowerCase(Locale.ROOT);
        for (CompiledMilestoneRule rule : rules) {
            if (!rule.role().equals(normalizedRole) || !rule.pattern().matcher(content).find()) {
                continue;
            }

            int maxContentLength = Math.max(0, properties.getMaxContentLength());
            push(sessionId, MilestoneVO.builder()
                    .type(rule.type())
                    .content(truncate(content, maxContentLength))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.debug(
                    "里程碑记录: sessionId={}, ruleId={}, type={}, content={}",
                    sessionId,
                    rule.id(),
                    rule.type(),
                    truncate(content, 100)
            );
            return;
        }
    }

    /**
     * 将里程碑加入指定会话的缓存队列。
     * @param sessionId    会话 ID
     * @param milestoneVO  里程碑事件
     */
    private void push(String sessionId, MilestoneVO milestoneVO) {
        conversationContextStore.addMilestone(sessionId, milestoneVO);
    }

    /**
     * 获取指定会话最近的 N 条里程碑事件。
     *
     * @param sessionId 会话 ID
     * @param limit     返回条数上限
     * @return 里程碑列表（按时间正序），无数据时返回空列表
     */
    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        return conversationContextStore.getRecentMilestones(sessionId, limit);
    }

    /**
     * 清除指定会话的全部里程碑记录。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        conversationContextStore.clearMilestones(sessionId);
    }

    /**
     * 校验并预编译配置规则。Java List.sort 是稳定排序，优先级相同的规则
     * 会继续保持 YAML 中的声明顺序。
     */
    private List<CompiledMilestoneRule> compileRules(List<AgentContextProperties.MilestoneRule> configuredRules) {

        if (configuredRules == null || configuredRules.isEmpty()) {
            return List.of();
        }

        List<CompiledMilestoneRule> compiledRules = new ArrayList<>(configuredRules.size());
        for (AgentContextProperties.MilestoneRule rule : configuredRules) {
            validateRule(rule);
            int flags = Pattern.UNICODE_CASE;
            if (rule.isCaseInsensitive()) {
                flags |= Pattern.CASE_INSENSITIVE;
            }

            try {
                compiledRules.add(new CompiledMilestoneRule(
                        rule.getId().trim(),
                        rule.getRole().trim().toLowerCase(Locale.ROOT),
                        rule.getType(),
                        rule.getPriority(),
                        Pattern.compile(rule.getPattern(), flags)
                ));
            } catch (PatternSyntaxException exception) {
                throw new IllegalStateException(
                        "里程碑规则正则无效，ruleId=" + rule.getId(),
                        exception
                );
            }
        }

        compiledRules.sort(Comparator.comparingInt(CompiledMilestoneRule::priority).reversed());
        return List.copyOf(compiledRules);
    }

    private void validateRule(AgentContextProperties.MilestoneRule rule) {
        if (rule == null) {
            throw new IllegalStateException("里程碑规则不能为空");
        }
        if (rule.getId() == null || rule.getId().isBlank()) {
            throw new IllegalStateException("里程碑规则 id 不能为空");
        }
        if (rule.getRole() == null || rule.getRole().isBlank()) {
            throw new IllegalStateException("里程碑规则 role 不能为空，ruleId=" + rule.getId());
        }
        if (rule.getType() == null) {
            throw new IllegalStateException("里程碑规则 type 不能为空，ruleId=" + rule.getId());
        }
        if (rule.getPattern() == null || rule.getPattern().isBlank()) {
            throw new IllegalStateException("里程碑规则 pattern 不能为空，ruleId=" + rule.getId());
        }
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 表示。
     *
     * @param s   原始字符串，为 null 时返回空串
     * @param max 最大保留长度
     * @return 截断后的字符串
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 已完成角色归一化和正则编译、可直接用于运行时匹配的规则。 */
    private record CompiledMilestoneRule(
            String id,
            String role,
            MilestoneVO.Type type,
            int priority,
            Pattern pattern) {
    }

}
