package com.jasonlat.ai.domain.agent.service.prompt;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.jasonlat.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.jasonlat.ai.domain.agent.service.IChatContextService;
import com.jasonlat.ai.domain.agent.service.IIntentService;
import com.jasonlat.ai.domain.agent.service.IPromptService;
import com.jasonlat.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import com.jasonlat.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 提示词服务
 * <p>
 * 组合 DynamicPromptBuilder、MilestoneTracker、IChatContextService，
 * 向 case 层提供统一的提示词领域能力。
 * <p>
 * 上下文采集已下沉到 IChatContextService 的 Provider 体系；本类是 Prompt 组装收口：
 * 稳定上下文放用户消息前，实时上下文放用户消息后，并用 PromptEnvelope 保留原始消息。
 *
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    /** 动态提示词构建器——负责组装结构化消息前缀（OS/用户/目录/里程碑/最近命令） */
    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    /** 里程碑追踪器——检测并缓存用户纠偏、任务切换等关键事件，供动态 Prompt 引用 */
    @Resource
    private MilestoneTracker milestoneTracker;

    /** 上下文管理服务——聚合各 ContextProvider 输出，组装 PromptContextVO */
    @Resource
    private IChatContextService chatContextService;

    /** 意图服务——读取最近一次完整识别结果，供结构化提示渲染 */
    @Resource
    private IIntentService intentService;


    /**
     * 检测并记录里程碑事件（用户纠偏、任务切换、错误等）
     *
     * @param sessionId 对话会话 ID
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容
     */
    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    /**
     * 构建注入了动态上下文的用户消息
     * <p>
     * 内部完成以下工作：
     * <br/>1. 从 SSH 终端采集环境信息（OS、用户、工作目录）
     * <br/>2. 获取最近里程碑事件
     * <br/>3. 构建 PromptContextVO 并生成消息前缀
     * <br/>4. 将前缀与原始用户消息拼接
     *
     * @param userMessage       原始用户消息
     * @param sessionId         对话会话 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param recentCommands    最近执行的命令列表
     * @return 注入了动态上下文的用户消息
     */
    @Override
    @Deprecated
    public String buildEnrichedMessage(String userMessage, String sessionId,String userId, String terminalSessionId, List<String> recentCommands) {
        return buildEnrichedMessage(userMessage, sessionId,userId, terminalSessionId, recentCommands, null);
    }

    /**
     * 构建注入了动态上下文的用户消息（富化消息）。
     * 前缀为空（第一轮无历史）时直接返回原始用户消息。
     *
     * @param userMessage        原始用户消息
     * @param sessionId          对话会话 ID
     * @param terminalSessionId  SSH 终端会话 ID（可为 null）
     * @param recentCommands     最近执行的命令列表
     * @param messageHistory     对话历史记录
     * @return 注入了动态上下文的用户消息
     */
    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String userId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory) {
        // 向后兼容：无意图标签的重载，委托给带 intentLabel 的版本（传 null）
        return buildEnrichedMessage(userMessage, sessionId, userId, terminalSessionId, recentCommands, messageHistory, null);
    }


    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String userId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String intentLabel) {
        // 统一组装：Provider 分层结果 -> 稳定前缀 / 动态尾部 -> PromptEnvelope 定界用户原文。
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(sessionId, userId, terminalSessionId, messageHistory);
        promptContextVO.setRecentCommands(recentCommands);
        promptContextVO.setIntentLabel(intentLabel);
        promptContextVO.setIntentResult(resolveIntentResult(sessionId));
        promptContextVO.setIntentLabel(resolveIntentLabel(intentLabel, promptContextVO.getIntentResult()));

        String stableContext = dynamicPromptBuilder.buildStableContext(promptContextVO);
        String dynamicContext = dynamicPromptBuilder.buildEphemeralContext(promptContextVO);
        return PromptEnvelope.compose(stableContext, userMessage, dynamicContext);
    }

    /**
     * 优先读取 ContextTracker 保存的完整识别结果；没有完整结果时兼容旧 intentLabel。
     */
    private IntentResultVO resolveIntentResult(String sessionId) {
        return intentService.getLastIntentResult(sessionId);
    }

    private String resolveIntentLabel(String intentLabel, IntentResultVO result) {
        if (result != null) {
            return result.getIntent() != null ? result.getIntent().name() : null;
        }
        if (intentLabel == null || intentLabel.isBlank()) {
            return null;
        }
        try {
            IntentTypeEnumVO.valueOf(intentLabel);
            return intentLabel;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 清除指定会话的里程碑记录
     *
     * @param sessionId 对话会话 ID
     */
    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }

}
