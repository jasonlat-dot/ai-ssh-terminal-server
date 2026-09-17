package com.jasonlat.ai.domain.agent.service.amory.matter.session.model;

import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * CustomAdkSessionService 的内部运行期快照。
 *
 * <p>它不是业务会话历史，也不是一次 {@code runAsync} 正在修改的 live Session：</p>
 * <ul>
 *     <li>业务历史由 ConversationContextStore 维护，是跨请求的唯一事实来源；</li>
 *     <li>本快照保存投影后的 ADK events 与运行 state，用于创建下一次 live Session；</li>
 *     <li>live Session 使用独立 events 列表，避免流式执行过程中直接污染快照。</li>
 * </ul>
 *
 * <p>同一快照涉及 events、state、lastUpdateTime 的组合更新时，由 SessionService
 * 使用 {@code synchronized (snapshot)} 保证原子性。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSnapshot {

    /** appName、userId、sessionId 组成的 ADK 会话唯一键。 */
    private SessionKey sessionKey;

    /**
     * 当前运行状态，例如 SSH terminalSessionId 和 ADK stateDelta。
     * 构造 live Session 时有意共享该 State，使 ToolContext 能读取最新状态。
     */
    private State state;

    /**
     * 供 ADK invocation 使用的事件投影；可能已经过净化、截断和按轮次裁剪，
     * 不应被当作完整业务聊天记录。
     */
    private List<Event> rawEvents;

    /** 最近一次投影或非 partial 事件追加的时间。 */
    private Instant lastUpdateTime;

}
