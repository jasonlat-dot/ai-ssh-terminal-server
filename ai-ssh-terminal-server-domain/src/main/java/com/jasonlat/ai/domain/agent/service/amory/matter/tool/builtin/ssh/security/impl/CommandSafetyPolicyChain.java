package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.impl;

import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.CommandSafetyPolicy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * SSH 命令安全责任链的统一入口。
 * <p>全部 Spring 策略统一按 getOrder 升序排列，具体顺序由各节点自己声明。
 * 每次请求使用独立上下文；节点列表构造后冻结，能够被并发工具调用安全复用。</p>
 */
@Primary
@Component("commandSafetyPolicyChain")
public class CommandSafetyPolicyChain implements CommandSafetyPolicy {

    private final List<CommandSafetyPolicy> policies;

    /**
     * 收集策略节点并按其声明的 order 装配责任链。
     *
     * @param candidates Spring 收集的全部策略节点；Spring 不把正在构造的自身列入此集合
     */
    public CommandSafetyPolicyChain(List<CommandSafetyPolicy> candidates) {
        policies = candidates.stream()
                // 排除链组件，避免手动装配时把整条链作为自身节点导致递归调用。
                .filter(policy -> !(policy instanceof CommandSafetyPolicyChain))
                // 所有业务节点使用同一排序规则；相同 order 保留候选集合原有顺序。
                .sorted(Comparator.comparingInt(CommandSafetyPolicy::getOrder))
                // 冻结节点列表，避免运行期间修改装配结果。
                .toList();
    }

    /**
     * 兼容字符串入口；未提供终端时将在终端节点返回前置条件失败。
     * @param command 原始命令
     * @return 责任链最终结论
     */
    @Override
    public CommandSafetyDecision evaluate(String command) {
        return evaluate(CommandSafetyContext.of(command, null, null, "unknown"));
    }

    /**
     * 依次执行节点，首次拒绝即结束，全部允许后才放行。
     *
     * @param context 本次检查的原始命令、终端及任务信息
     * @return 首个拒绝结果，或全部节点通过后的允许结果
     */
    @Override
    public CommandSafetyDecision evaluate(CommandSafetyContext context) {
        Objects.requireNonNull(context, "命令安全上下文不能为空");
        // 链入口始终从原始命令开始，不能信任调用方预填的 normalizedCommand。
        CommandSafetyContext current = context.withNormalizedCommand(context.command());
        for (CommandSafetyPolicy policy : policies) {
            // 不捕获未知策略异常，也不接受 null；只有明确允许才继续，避免静默放行。
            CommandSafetyDecision decision = Objects.requireNonNull(
                    policy.evaluate(current), "命令安全节点不能返回 null");
            if (!decision.isAllowed()) {
                return decision;
            }
            // 本地节点产生的规范化文本供后续使用，原始执行命令始终不被替换。
            current = current.withNormalizedCommand(decision.getNormalizedCommand());
        }
        return CommandSafetyDecision.allow(current.normalizedCommand());
    }
}
