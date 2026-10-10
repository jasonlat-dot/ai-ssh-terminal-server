package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security;

import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyContext;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security.valobj.CommandSafetyDecision;

/**
 * SSH 命令执行前的安全策略边界。
 *
 * <p>所有由 AI 发起的 SSH 命令都必须先经过该接口判定，只有返回
 * {@code allowed=true} 时才能进入真正的 SSH 执行通道。策略只负责判定，
 * 不负责执行命令，也不依赖模型自行声明命令是否安全。</p>
 */
public interface CommandSafetyPolicy {

    /**
     * 判断命令是否允许进入 SSH 执行通道。
     *
     * <p>保留字符串入口以兼容已有本地规则调用。完整责任链需要终端与任务信息，
     * 应通过 {@link #evaluate(CommandSafetyContext)} 调用。</p>
     *
     * @param command 原始命令
     * @return 安全判定结果
     */
    CommandSafetyDecision evaluate(String command);

    /**
     * 执行当前节点的检查。
     *
     * <p>返回允许表示继续下一节点，返回拒绝表示立即结束整条链。默认实现
     * 将检测文本交给已有字符串入口，因此本地规则无需依赖 Agent 上下文。
     * 本地硬规则异常必须阻止执行；外部辅助判断的降级由其节点自行处理。</p>
     *
     * @param context 当前检查上下文，包含上一节点传递的规范化命令
     * @return 当前节点的允许或拒绝结果，不允许返回 null
     */
    default CommandSafetyDecision evaluate(CommandSafetyContext context) {
        return evaluate(context.normalizedCommand());
    }

    /**
     * 获取当前节点的执行顺序，数值越小越先执行。
     *
     * <p>全部节点都通过此值参与统一排序，责任链不指定任何节点的固定位置。
     * 默认 100；各实现可以覆盖该方法声明自己的执行顺序。</p>
     *
     * @return 当前节点的排序值
     */
    default int getOrder() {
        return 100;
    }
}
