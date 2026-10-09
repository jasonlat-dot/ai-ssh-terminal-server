package com.jasonlat.ai.domain.agent.service;

import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentRequestVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.jasonlat.ai.domain.agent.model.valobj.intent.TaskStateVO;

/**
 * 意图识别服务接口
 * <p>
 * 意图识别子系统的对外门面，由 {@code IntentService} 实现。能力分三组：
 * <ol>
 *   <li>分类：{@link #classify} —— 规则 → LLM 级联识别用户意图</li>
 *   <li>反馈回路：{@link #reportFeedback} —— 工具执行后回报，失败可触发重分类</li>
 *   <li>任务态：{@link #getTaskState}/{@link #updateTaskState} —— 支撑 CONTINUE 与多步任务</li>
 * </ol>
 * <p>
 * 除了分类入口外，新增：
 * <ul>
 *   <li>{@link #reportFeedback} 反馈回路：下游执行后回报成功/失败，失败可触发重分类</li>
 *   <li>{@link #getTaskState} / {@link #updateTaskState} 任务态：支撑 CONTINUE 与多步任务</li>
 * </ul>
 */
public interface IIntentService {

    /**
     * 对用户消息进行意图分类
     * @return 意图识别结果
     */
    IntentResultVO classify(IntentRequestVO intentRequestVO) throws Exception;

    /**
     * 反馈回路：下游节点执行工具后回报结果。
     * <p>
     * 当 success=false 且当前意图置信度不高时，IntentService 可决定重分类，
     * 返回新的意图结果；若无需重分类则返回 null。
     *
     * @param sessionId  会话 ID
     * @param lastIntent 上一次识别的意图
     * @param success    本轮工具执行是否成功
     * @param toolResult 工具执行结果文本（用于判断是否需要重分类）
     * @return 重分类后的新意图，或 null 表示维持原意图
     */
    IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent, boolean success, String toolResult);

    /**
     * 反馈回路：使用完整工具执行上下文判断是否发生意图偏差。
     *
     * <p>与旧签名相比，本方法额外接收用户原始消息、工具名和命令，
     * 使 Jev/Laya 能够区分“当前意图识别错误”与“意图正确但工具执行失败”。</p>
     *
     * @param sessionId       当前会话 ID，用于更新会话反馈状态
     * @param lastIntent      工具执行前的意图识别结果
     * @param userMessage     用户本轮原始消息，用于判断真实目标
     * @param toolName        实际执行的工具名称
     * @param command         工具执行的命令或关键输入；非命令类工具允许为空
     * @param reportedSuccess 工具协议返回的原始 success 字段
     * @param toolResult      工具返回的文本结果
     * @return 需要重分类时返回新的意图结果，否则返回 null
     */
    IntentResultVO reportFeedback(
            String sessionId,
            IntentResultVO lastIntent,
            String userMessage,
            String toolName,
            String command,
            boolean reportedSuccess,
            String toolResult
    );

    /**
     * 获取当前会话的任务态
     */
    TaskStateVO getTaskState(String sessionId);

    /**
     * 更新当前会话的任务态
     */
    void updateTaskState(String sessionId, TaskStateVO taskState);

    /**
     * 获取最近一次完整意图识别结果，用于结构化 Prompt 提示；无历史时返回 null。
     */
    IntentResultVO getLastIntentResult(String sessionId);

}
