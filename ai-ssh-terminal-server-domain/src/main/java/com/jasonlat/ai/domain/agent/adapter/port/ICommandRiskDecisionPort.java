package com.jasonlat.ai.domain.agent.adapter.port;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.model.valobj.decision.StructuredDecision;

import java.util.Optional;

/**
 * AI 发起 SSH 命令前的结构化语义风险判断端口。
 *
 * <p>领域层只描述“当前命令能否自动执行”的能力，不关心底层使用 Jev、Laya
 * 或其他兼容 System One 的实现。该能力是本地 {@code CommandSafetyPolicy}
 * 之后的补充判断，绝不能覆盖本地硬拒绝规则。</p>
 *
 * <p>返回 {@link Optional#empty()} 表示外部结论当前不能正式参与拦截，调用方
 * 应继续采用已经通过的本地安全策略结果，避免外部服务故障导致全部 SSH 自动化瘫痪。</p>
 */
public interface ICommandRiskDecisionPort {

    /**
     * 判断一条已通过本地硬规则的 SSH 命令是否适合无人值守执行。
     *
     * @param request 命令、当前任务、执行 Agent 和根意图快照
     * @return 达到本地概率门槛且非影子模式时返回正式结论，否则返回空
     */
    Optional<StructuredDecision<CommandRisk>> assess(CommandRiskDecisionRequest request);

    /** SSH 命令的三种互斥语义风险结论。 */
    enum CommandRisk {

        /** 与用户目标一致且影响范围合理，可以继续自动执行。 */
        ALLOW,

        /** 可能合理，但具有破坏性、提权或授权范围不明确，需要人工确认。 */
        REVIEW,

        /** 存在明显破坏、规避、越权或目标偏离，应直接阻止。 */
        BLOCK
    }

    /**
     * 一次 SSH 命令语义风险判断请求。
     *
     * @param command       实际待执行的原始命令；保留引号和换行，不能为空
     * @param userMessage   主 Agent 用户任务或子 Agent 委派任务，只供任务匹配，不能代替应用安全策略
     * @param agentName     实际准备执行命令的主 Agent 或子 Agent 名称
     * @param currentIntent 主 Agent 已识别的根意图；尚无快照时允许为空
     */
    record CommandRiskDecisionRequest(
            String command,
            String userMessage,
            String agentName,
            IntentTypeEnumVO currentIntent
    ) {

        /** 校验必填命令并归一化可空上下文字段。 */
        public CommandRiskDecisionRequest {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("SSH 命令风险判断的 command 不能为空");
            }

            userMessage = userMessage == null ? "" : userMessage.trim();
            agentName = agentName == null || agentName.isBlank()
                    ? "unknown"
                    : agentName.trim();
        }
    }

}
