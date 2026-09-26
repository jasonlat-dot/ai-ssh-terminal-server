package com.jasonlat.ai.domain.agent.service.amory.matter.tool.builtin.ssh.security;

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
     * <p>实现需要采用失败关闭（fail closed）原则：空命令、非法配置或明确
     * 命中拒绝规则时必须返回拒绝结果，不能因为解析异常而放行。</p>
     *
     * @param command 原始命令
     * @return 安全判定结果
     */
    CommandSafetyDecision evaluate(String command);
}
