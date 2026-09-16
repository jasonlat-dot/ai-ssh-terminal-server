package com.jasonlat.ai.domain.agent.model.valobj.properties;

import com.jasonlat.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 上下文管理配置。
 *
 * <p>对应配置前缀 {@code ai.agent.context}，集中管理消息裁剪和里程碑识别规则。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.context")
public class AgentContextProperties {

    private Reducer reducer = new Reducer();
    private Milestone milestone = new Milestone();

    /** 消息历史裁剪配置。 */
    @Data
    public static class Reducer {

        /** 无论预算是否充足，都需要保留的最近消息条数。 */
        private int minimumRecentMessages = 5;

        /** Assistant/Model 消息超过该字符数后降为 LOW 优先级。 */
        private int longAssistantThreshold = 2048;

        /** 工具结果命中任意关键词后提升为 CRITICAL 优先级。 */
        private List<String> errorKeywords = new ArrayList<>(List.of(
                "error",
                "failed",
                "exception",
                "permission denied",
                "operation not permitted"
        ));

        /** 用户消息命中任意配置文件后缀后提升为 HIGH 优先级。 */
        private List<String> importantPathSuffixes = new ArrayList<>(List.of(
                ".conf",
                ".yml",
                ".yaml",
                ".properties"
        ));
    }

    /** 里程碑识别与注入配置。 */
    @Data
    public static class Milestone {

        /** 是否启用里程碑识别和 Prompt 注入。 */
        private boolean enabled = true;

        /** 每次构建 Prompt 时注入的最近里程碑数量。 */
        private int recentLimit = 10;

        /** 单条里程碑内容允许保存的最大字符数。 */
        private int maxContentLength = 1024;

        /** 按 priority 从高到低匹配，优先级相同则保持 YAML 声明顺序。 */
        private List<MilestoneRule> rules = defaultMilestoneRules();
    }

    /** 一条里程碑识别规则。 */
    @Data
    public static class MilestoneRule {

        /** 稳定规则编号，用于日志定位。 */
        private String id;

        /** 适用消息角色，例如 user 或 tool。 */
        private String role;

        /** 命中后生成的里程碑类型。 */
        private MilestoneVO.Type type;

        /** 规则优先级，数值越大越先匹配。 */
        private int priority;

        /** 是否忽略英文大小写。 */
        private boolean caseInsensitive = true;

        /** Java 正则表达式，使用 Matcher.find() 搜索消息内容。 */
        private String pattern;
    }

    private static List<MilestoneRule> defaultMilestoneRules() {
        List<MilestoneRule> rules = new ArrayList<>();
        rules.add(rule(
                "user-task-change",
                "user",
                MilestoneVO.Type.TASK_CHANGE,
                100,
                false,
                "不对|不是这样|改一下|换个思路|换种方式|错了"
        ));
        rules.add(rule(
                "user-task-complete",
                "user",
                MilestoneVO.Type.TASK_COMPLETE,
                90,
                false,
                "完成了|搞定|结束|好了"
        ));
        rules.add(rule(
                "user-correction",
                "user",
                MilestoneVO.Type.USER_CORRECTION,
                80,
                false,
                "不要|停止|别"
        ));
        rules.add(rule(
                "tool-error",
                "tool",
                MilestoneVO.Type.ERROR,
                100,
                true,
                "error|failed|exception|permission denied|not found|refused"
        ));
        return rules;
    }

    private static MilestoneRule rule(
            String id, String role, MilestoneVO.Type type,
            int priority, boolean caseInsensitive, String pattern) {

        MilestoneRule rule = new MilestoneRule();
        rule.setId(id);
        rule.setRole(role);
        rule.setType(type);
        rule.setPriority(priority);
        rule.setCaseInsensitive(caseInsensitive);
        rule.setPattern(pattern);

        return rule;
    }
}
