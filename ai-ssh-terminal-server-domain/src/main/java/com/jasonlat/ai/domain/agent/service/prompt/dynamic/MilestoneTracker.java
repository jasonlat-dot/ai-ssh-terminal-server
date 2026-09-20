package com.jasonlat.ai.domain.agent.service.prompt.dynamic;

import com.jasonlat.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.jasonlat.ai.domain.agent.model.valobj.properties.AgentContextProperties;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.jasonlat.ai.domain.agent.service.context.cache.ConversationContextStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
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
    private final IChatHistoryRepository chatHistoryRepository;

    /**
     * 创建里程碑追踪器，并在应用启动时完成规则校验和正则预编译。
     * 配置错误会直接阻止应用启动，避免运行期间悄悄漏记里程碑。
     */
    public MilestoneTracker(
            ConversationContextStore conversationContextStore,
            AgentContextProperties contextProperties, IChatHistoryRepository chatHistoryRepository) {

        this.conversationContextStore = conversationContextStore;
        this.properties = contextProperties.getMilestone();
        this.chatHistoryRepository = chatHistoryRepository;
        this.rules = compileRules(this.properties.getRules());
    }

    /**
     * 检测并记录里程碑事件。
     * <p>
     * 按角色分别识别关键事件，记录到会话级缓存。
     * <p>
     * 案例 1：用户纠偏
     * <pre>
     *   detectAndRecord("session-001", "user", "不对，应该是看 /var/log/nginx/error.log")
     *   -> 匹配 "不对|不是这样|改一下|换个思路"
     *   -> 记录 TASK_CHANGE: "不对，应该是看 /var/log/nginx/error.log"
     * </pre>
     * <p>
     * 案例 2：工具报错
     * <pre>
     *   detectAndRecord("session-001", "tool", "Error: permission denied")
     *   -> 匹配 "error|failed|exception"
     *   -> 记录 ERROR: "Error: permission denied"
     * </pre>
     * <p>
     * 案例 3：任务完成
     * <pre>
     *   detectAndRecord("session-001", "user", "完成了，帮大忙了！")
     *   -> 匹配 "完成了|搞定|结束"
     *   -> 记录 TASK_COMPLETE: "搞定，帮大忙了！"
     * </pre>
     * */
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
            MilestoneVO milestone = MilestoneVO.builder()
                    .type(rule.type())
                    .content(truncate(content, maxContentLength))
                    .timestamp(System.currentTimeMillis())
                    .build();
            push(sessionId, milestone);
            if ((rule.type() == MilestoneVO.Type.TASK_CHANGE || rule.type().equals(MilestoneVO.Type.TASK_COMPLETE)) && "user".equals(normalizedRole)) {
                conversationContextStore.updateCurrentTask(sessionId, content);
                log.info("会话当前任务已更新 sessionId={}, ruleId={}", sessionId, rule.id());
            }
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

        // 2. 数据库持久化：try-catch 旁路写入，失败不影响主流程。
        // 旁路写入原则：记忆持久化是"锦上添花"而非"生死攸关"，不能因为 DB 异常导致 Agent 不可用。
        try {
            chatHistoryRepository.saveMilestone(sessionId, milestoneVO);
        } catch (Exception e) {
            log.error("保存里程碑失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取指定会话最近的 N 条里程碑事件。
     *
     * @param sessionId 会话 ID
     * @param limit     返回条数上限
     * @return 里程碑列表（按时间正序），无数据时返回空列表
     */
    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        List<MilestoneVO> recentMilestones = conversationContextStore.getRecentMilestones(sessionId, limit);
        if (!recentMilestones.isEmpty()) {
            return recentMilestones;
        }

        // 降级到 DB 查询
        try {
            recentMilestones = chatHistoryRepository.getRecentMilestones(sessionId, limit);
            if (recentMilestones != null && !recentMilestones.isEmpty()) {
                // DB 查询返回的是倒序，这里转为正序返回
                List<MilestoneVO> reversed = new ArrayList<>(recentMilestones);
                Collections.reverse(reversed);
                return reversed;
            }
        } catch (Exception e) {
            log.error("获取近期里程碑失败 sessionId={}", sessionId, e);
        }

        return recentMilestones;
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
