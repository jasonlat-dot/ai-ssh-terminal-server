package com.jasonlat.ai.domain.agent.service.amory.matter.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.model.SessionSnapshot;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl.SshExecuteAdkTool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 定义 ADK Session 服务。
 * <p>这个类并不是去“1:1 复刻” ADK 原生/默认 Session 的完整存储语义，
 * 而是围绕当前 ReAct + SSH 场景，做了一层<strong>面向运行期的轻量化会话治理</strong>。
 *
 * <p>为什么要自己接管 Session？
 * 因为当前工程在业务层已经做了动态 Prompt 上下文增强，例如：
 * <pre>
 *   [系统环境]
 *   [最近执行的命令]
 *   [关键事件]
 *   ---
 *   用户原始问题
 * </pre>
 *
 * <p>这类“富化消息”适合发给模型做推理，但并不适合再被 ADK 原样存回 Session。
 * 如果直接回灌，会带来几个典型问题：
 * <pre>
 *   1. 用户原始问题被动态前缀污染
 *   2. 相同上下文在业务层和框架层重复保存
 *   3. tool / assistant 长文本让 Session 持续膨胀
 *   4. 对话轮次越来越长，后续取历史成本越来越高
 * </pre>
 *
 * <p>所以，这个类相对“原生 ADK Session 使用方式”，做了几项有意识的简化：
 * <pre>
 *   1. 只保留运行期轻量 Session，不做持久化落库
 *   2. 不追求保存完整原文历史，而是保存“够用”的净化后历史
 *   3. 不把动态 Prompt 前缀原样写回 Session，避免上下文重复
 *   4. 不保留无限长的 tool/assistant 文本，而是按类型截断
 *   5. 不保留无限轮对话，而是只保留最近 MAX_TURNS 轮、最多 MAX_EVENTS 条
 *   6. 只同步 event 中真正有价值的 stateDelta，维持最新运行态 state
 * </pre>
 *
 * <p>你可以把它理解成：放在 ADK Session 前面的一道“净化器 + 限流器 + 修剪器”。
 * 目标不是把所有历史都留下，而是让框架层会话<strong>干净、轻量、可控</strong>，
 * 同时避免与业务侧 {@code ConversationContextStore} 管理的上下文发生重复和打架。
 */
@Component
@Slf4j
public class CustomAdkSessionService implements BaseSessionService {

    /**
     * Session 的跨轮次 event 软上限。超过时只删除完整旧轮次；
     * 单个尚未结束的长轮次允许暂时超过上限，避免拆散工具调用对。
     */
    private static final int MAX_EVENTS = 20;
    /** assistant/model 文本的最大保留长度。 */
    private static final int MAX_ASSISTANT_TEXT = 2048;
    /** tool 结果文本的最大保留长度。 */
    private static final int MAX_TOOL_TEXT = 1024;
    /** 最多保留的用户轮次数；轮次是比“消息条数”更自然的对话单位。 */
    private static final int MAX_TURNS = 4;

    /**
     * 运行期 Session 三级索引：appName -> userId -> sessionId -> SessionSnapshot。
     * 三级键共同构成 ADK Session 的隔离边界，不能只使用 sessionId 建立全局索引。
     */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>>> sessions = new ConcurrentHashMap<>();
    /** 预留的 user 级别状态存储。 */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>> userState = new ConcurrentHashMap<>();
    /** 预留的 app 级别状态存储。 */
    private final ConcurrentMap<String, ConcurrentMap<String, Object>> appState = new ConcurrentHashMap<>();

    /**
     * 用业务层裁剪后的历史重建下一次 ADK invocation 的只读起点。
     *
     * <p>ConversationContextStore 才是跨请求历史的唯一事实来源；这里保存的 events
     * 只是 ADK 本次执行所需的投影。旧 invocation 的 FunctionCall/FunctionResponse
     * 会在这里被覆盖，但本次 runAsync 内仍可正常追加并驱动 ADK 自己的 ReAct 循环。</p>
     *
     * <p>工具结果不直接投影为孤立 FunctionResponse，避免破坏 ADK 协议配对；它们由
     * ToolResultProvider 以摘要形式进入动态 Prompt。</p>
     */
    public void prepareInvocation(
            String appName,
            String userId,
            String sessionId,
            List<Map<String, Object>> businessHistory,
            String terminalSessionId) {

        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        if (snapshot == null) {
            throw new IllegalStateException("session not found: " + appName + "/" + userId + "/" + sessionId);
        }

        List<Event> projectedEvents = new ArrayList<>();
        if (businessHistory != null) {
            for (Map<String, Object> message : businessHistory) {
                Event event = projectBusinessMessage(message);
                if (event != null) {
                    projectedEvents.add(event);
                }
            }
        }

        /*
         * prepareInvocation 与 appendEvent 都会成组修改 events/state/updateTime。
         * ConcurrentMap 和 CopyOnWriteArrayList 只能保证单次操作安全，无法保证这一组操作原子，
         * 因此仍以 snapshot 作为锁，避免同一 Session 的投影和事件追加相互穿插。
         */
        synchronized (snapshot) {
            snapshot.getRawEvents().clear();
            snapshot.getRawEvents().addAll(projectedEvents);
            if (terminalSessionId == null || terminalSessionId.isBlank()) {
                snapshot.getState().remove(SshExecuteAdkTool.TERMINAL_SESSION_STATE_KEY);
            } else {
                snapshot.getState().put(SshExecuteAdkTool.TERMINAL_SESSION_STATE_KEY, terminalSessionId);
            }
            snapshot.setLastUpdateTime(Instant.now());
        }
        log.info("ADK invocation 已准备 appName={}, userId={}, sessionId={}, terminalSessionId={}, businessMessages={}, projectedEvents={}",
                appName, userId, sessionId,
                terminalSessionId, businessHistory == null ? 0 : businessHistory.size(), projectedEvents.size());
    }

    /**
     * 将业务消息转换为本次 ADK invocation 可读取的基础文本事件。
     *
     * <p>这里只投影 user 与 assistant/model 文本。业务历史中的 tool 消息如果脱离原始
     * FunctionCall 直接投影，会形成孤立 FunctionResponse，因此由工具摘要通过动态 Prompt
     * 提供，而不是伪造 ADK 工具协议事件。</p>
     *
     * @param message ConversationContextStore 中的一条业务消息
     * @return 可投影的 ADK Event；空内容或不支持的角色返回 null
     */
    private Event projectBusinessMessage(Map<String, Object> message) {
        if (message == null) {
            return null;
        }
        String role = Objects.toString(message.get("role"), "");
        String content = Objects.toString(message.get("content"), "");
        if (content.isBlank()) {
            return null;
        }

        String adkRole;
        String author;
        if ("user".equalsIgnoreCase(role)) {
            adkRole = "user";
            author = "user";
        } else if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
            adkRole = "model";
            author = "assistant";
        } else {
            return null;
        }

        return Event.builder()
                .id(Event.generateEventId())
                .author(author)
                .content(Content.builder()
                        .role(adkRole)
                        .parts(List.of(Part.fromText(content)))
                        .build())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建一个新的轻量 Session 快照。
     *
     * <p>这里的设计很克制：
     * 只初始化当前会话运行必需的 state、event 容器和更新时间，
     * 不做更重的持久化、归档、索引等动作。
     *
     * <p>案例：
     * <pre>
     *   appName   = ssh-app
     *   userId    = user-001
     *   sessionId = react-session-01
     *   创建后得到：
     *   sessions["ssh-app"]["user-001"]["react-session-01"] = SessionSnapshot
     * </pre>
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param initialState 初始状态
     * @param sessionId 会话 ID，若为空则自动生成
     * @return 对外暴露的 ADK Session
     */
    @Override
    public Single<Session> createSession(String appName, String userId, ConcurrentMap<String, Object> initialState, String sessionId) {
        SessionSnapshot snapshot = buildAndStoreSessionSnapshot(appName, userId, initialState, sessionId);
        return Single.just(toSession(snapshot, Optional.empty()));
    }

    public void putSession(String appName, String userId, ConcurrentMap<String, Object> initialState, String sessionId) {
        buildAndStoreSessionSnapshot(appName, userId, initialState, sessionId);
    }

    /**
     * 公共抽取方法：构建Snapshot + 写入三层内存map（sessions / userState / appState）
     */
    private SessionSnapshot buildAndStoreSessionSnapshot(String appName, String userId,
                                                         ConcurrentMap<String, Object> initialState,
                                                         String sessionId) {
        String finalSessionId = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;

        State state = new State(initialState == null ? new ConcurrentHashMap<>() : initialState);
        SessionSnapshot snapshot = SessionSnapshot.builder()
                .sessionKey(new SessionKey(appName, userId, finalSessionId))
                .state(state)
                .rawEvents(new CopyOnWriteArrayList<>())
                .lastUpdateTime(Instant.now())
                .build();

        // 写入 sessions 三层嵌套map
        sessions.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(finalSessionId, snapshot);

        // 初始化userState
        userState.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        // 初始化appState
        appState.computeIfAbsent(appName, k -> new ConcurrentHashMap<>());

        return snapshot;
    }


    /**
     * 查询指定会话，并按配置返回事件子集。
     *
     * <p>这里支持两种最常见的轻量过滤：
     * <pre>
     *   afterTimestamp  只取某个时间点之后的事件
     *   numRecentEvents 只取最近 N 条事件
     * </pre>
     *
     * <p>案例：
     * <pre>
     *   一个 Session 当前有 12 条事件
     *   如果 numRecentEvents = 5
     *   最终只返回后 5 条，而不是把 12 条全量带回去
     * </pre>
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param configOpt 查询配置
     * @return Session，若不存在则返回空
     */
    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        if (snapshot == null) {
            return Maybe.empty();
        }

        // 在副本上过滤，查询参数不能反向裁剪内部快照。
        List<Event> events = new ArrayList<>(snapshot.getRawEvents());
        if (configOpt.isPresent()) {
            GetSessionConfig config = configOpt.get();
            if (config.afterTimestamp().isPresent()) {
                Instant ts = config.afterTimestamp().get();
                events = events.stream()
                        .filter(event -> Instant.ofEpochMilli(event.timestamp()).isAfter(ts))
                        .collect(Collectors.toList());
            }
            if (config.numRecentEvents().isPresent()) {
                int n = config.numRecentEvents().get();
                if (n >= 0 && events.size() > n) {
                    events = new ArrayList<>(events.subList(events.size() - n, events.size()));
                }
            }
        }

        return Maybe.just(toSession(snapshot, Optional.of(events)));
    }

    /**
     * 列出某个用户在当前应用下的全部 Session。
     *
     * <p>当前实现只做内存级遍历与组装，
     * 不做分页、排序、远程存储读取等更重的能力。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @return Session 列表
     */
    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        List<Session> sessionList = new ArrayList<>();
        ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
        if (appSessions != null) {
            ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
            if (userSessions != null) {
                userSessions.values().forEach(snapshot -> sessionList.add(toSession(snapshot, Optional.empty())));
            }
        }

        return Single.just(ListSessionsResponse.builder()
                .sessions(ImmutableList.copyOf(sessionList))
                .build());
    }

    /**
     * 删除指定 Session。
     *
     * <p>当前实现只从内存索引中移除快照，不做更复杂的级联清理，
     * 因为当前阶段 Session 的定位就是“运行期轻量会话”。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return Completable
     */
    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> {
            ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
            if (appSessions != null) {
                ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
                if (userSessions != null) {
                    // 仅删除目标快照；父级空 Map 保留，避免并发创建会话时发生索引竞争。
                    userSessions.remove(sessionId);
                }
            }
        });
    }

    /**
     * 列出指定 Session 的事件列表。
     *
     * <p>注意这里返回的是已经过净化、截断、裁剪后的 rawEvents，
     * 而不是最初送进 ADK 的“完整原始富化消息”。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 事件响应
     */
    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        List<Event> events = snapshot == null ? List.of() : new ArrayList<>(snapshot.getRawEvents());
        return Single.just(ListEventsResponse.builder()
                .events(ImmutableList.copyOf(events))
                .build());
    }

    /**
     * 追加一条事件，并在写入前完成本类最核心的会话治理逻辑。
     *
     * <p>执行顺序：
     * <pre>
     *   1. partial 检查      不持久化流式中间片段
     *   2. normalizeEvent    补齐时间戳与 id
     *   3. ADK 默认 append   更新当前 invocation 的 events/state
     *   4. prepareForStorage 生成净化、截断但保留工具协议结构的快照版本
     *   5. rawEvents.add     写入跨请求快照
     *   6. merge/trim        合并状态并按完整轮次裁剪
     *   7. updateTime        更新时间
     * </pre>
     *
     * <p>案例：
     * <pre>
     *   输入 user event：
     *   [系统环境]
     *   系统: Linux
     *   ---
     *   帮我看 nginx 日志
     *
     *   写入后实际保留：
     *   帮我看 nginx 日志
     * </pre>
     *
     * @param session 当前 Session
     * @param event 待追加事件
     * @return 最终写入的规范化事件
     */
    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(event, "event cannot be null");

        /*
         * 与 ADK BaseSessionService 的默认契约保持一致：流式 partial 事件只用于向外
         * 传递，不能写入 Session。否则同一次流式回复会被保存成大量重复片段。
         */
        if (event.partial().orElse(false)) {
            return Single.just(event);
        }

        SessionKey key = session.sessionKey();
        SessionSnapshot snapshot = findSnapshot(key.appName(), key.userId(), key.id());
        if (snapshot == null) {
            return Single.error(new IllegalStateException("session not found: " + key));
        }

        Event liveEvent = normalizeEvent(event);

        /*
         * 先调用 ADK 默认实现，把事件和 stateDelta 写进本次 invocation 正在使用的
         * Session。工具执行后的下一次模型请求正是从这个对象读取 FunctionResponse；
         * 如果只更新自定义快照，当前 Session 会一直停留在工具调用之前。
         */
        return BaseSessionService.super.appendEvent(session, liveEvent)
                .map(appendedEvent -> {
                    /*
                     * 快照用于跨请求恢复，可以净化和截断；当前运行中的 live Session
                     * 必须保留完整结构，两者不能共享同一个可变 events List。
                     */
                    Event storageEvent = prepareEventForStorage(appendedEvent);
                    synchronized (snapshot) {
                        snapshot.getRawEvents().add(storageEvent);
                        mergeStateDelta(snapshot, appendedEvent);
                        trimEvents(snapshot.getRawEvents());
                        snapshot.setLastUpdateTime(resolveUpdateTime(appendedEvent));
                    }
                    return appendedEvent;
                });
    }

    /**
     * 根据 appName / userId / sessionId 查找 SessionSnapshot。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return SessionSnapshot，不存在则返回 null
     */
    private SessionSnapshot findSnapshot(String appName, String userId, String sessionId) {
        ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
        if (appSessions == null) {
            return null;
        }
        ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
        if (userSessions == null) {
            return null;
        }
        return userSessions.get(sessionId);
    }

    /**
     * 将内部快照对象转换为对外暴露的 ADK Session。
     *
     * <p>内部真实存储用的是 {@link SessionSnapshot}，
     * 对外仍然按 ADK 的 Session 语义返回，方便 Runner 正常使用。
     *
     * @param snapshot 内部快照
     * @param eventsOverride 可选的事件覆盖集
     * @return ADK Session
     */
    private Session toSession(SessionSnapshot snapshot, Optional<List<Event>> eventsOverride) {
        List<Event> events = eventsOverride.orElseGet(snapshot::getRawEvents);
        Session session = Session.builder(snapshot.getSessionKey())
                // state 有意共享：ToolContext 对 state 的修改必须能回到同一快照；events 则必须隔离。
                .state(snapshot.getState())
                // 当前 invocation 使用独立列表；appendEvent 会分别更新 live Session 和快照。
                .events(new ArrayList<>(events))
                .build();
        session.lastUpdateTime(snapshot.getLastUpdateTime());
        return session;
    }

    /**
     * 规范化 Event，补齐时间戳和事件 ID。
     *
     * <p>这样后面做更新时间同步、时间过滤、事件追踪时会更稳定。
     *
     * @param event 原始事件
     * @return 规范化后的事件
     */
    private Event normalizeEvent(Event event) {
        Event normalized = event.toBuilder().build();
        if (normalized.timestamp() <= 0) {
            normalized.setTimestamp(System.currentTimeMillis());
        }
        if (normalized.id() == null || normalized.id().isBlank()) {
            normalized.setId(Event.generateEventId());
        }
        return normalized;
    }

    /**
     * 解析事件角色。
     *
     * <p>优先取 content.role，取不到时回退到 author。
     * 角色判断准确，后面的“净化 / 截断 / 裁剪”才会准确。
     *
     * @param event 事件
     * @return role
     */
    private String resolveRole(Event event) {
        return event.content().flatMap(Content::role).orElse(event.author());
    }

    /**
     * 生成跨请求存储版本的事件。
     *
     * <p>FunctionCall/FunctionResponse 是协议结构，必须优先按 Part 类型识别，不能仅
     * 依据 role。ADK 的 FunctionResponse 通常使用 role=user，但它不是用户新消息。</p>
     */
    private Event prepareEventForStorage(Event event) {
        if (hasFunctionResponse(event)) {
            return truncateFunctionResponseOutput(event, MAX_TOOL_TEXT);
        }

        if (isActualUserMessage(event)) {
            // 必须保留完整富化消息，否则动态上下文会在模型调用前被删除。
            return event.toBuilder().build();
//            return sanitizeUserEvent(event);
        }

        String role = resolveRole(event);
        if ("tool".equalsIgnoreCase(role)) {
            return truncateEventText(event, MAX_TOOL_TEXT);
        }
        if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
            return truncateEventText(event, MAX_ASSISTANT_TEXT);
        }
        return event;
    }

    /**
     * 对 user 事件做净化，去掉动态 Prompt 前缀，只保留原始用户问题。
     *
     * <p>这一步是“避免上下文重复”的关键动作之一。
     * 因为环境信息、最近命令、关键事件等内容，业务层已经维护了一份，
     * 不应该再在框架层 Session 里无限重复保存。
     *
     * <p>案例：
     * <pre>
     *   输入：
     *   [系统环境]
     *   系统: Linux
     *   [关键事件]
     *   - permission denied
     *   ---
     *   请继续查看 error.log
     *
     *   输出：
     *   请继续查看 error.log
     * </pre>
     *
     * @param event user 事件
     * @return 净化后的事件
     */
    private Event sanitizeUserEvent(Event event) {
        String text = event.stringifyContent();
        if (text.isBlank()) {
            return event;
        }

        String actualUserMessage = stripDynamicPrefix(text);
        if (actualUserMessage.equals(text)) {
            return event;
        }

        Event sanitized = event.toBuilder().build();
        Content content = sanitized.content().orElse(Content.builder().role("user").build());
        String role = content.role().orElse("user");
        sanitized.setContent(Content.builder()
                .role(role)
                .parts(List.of(Part.fromText(actualUserMessage)))
                .build());
        return sanitized;
    }

    /**
     * 从文本中剥离动态注入前缀。
     *
     * <p>优先按 {@code \n---\n} 分隔线切分；若没有分隔线，
     * 再根据典型前缀段落标签做启发式判断。
     *
     * <p>案例 1：
     * <pre>
     *   [系统环境]
     *   ...
     *   ---
     *   帮我分析日志
     *
     *   结果：帮我分析日志
     * </pre>
     *
     * <p>案例 2：
     * <pre>
     *   [关键事件]
     *   - 工具执行失败
     *   再试一次 ls /var/log
     *
     *   结果：再试一次 ls /var/log
     * </pre>
     *
     * @param text 原始文本
     * @return 剥离前缀后的文本
     */
    private String stripDynamicPrefix(String text) {
        if (text.contains("\n---\n")) {
            String[] parts = text.split("\\n---\\n", 2);
            return parts.length == 2 ? parts[1].trim() : text;
        }

        boolean looksLikeInjectedPrefix = text.startsWith("[系统环境]")
                || text.startsWith("[最近执行的命令]")
                || text.startsWith("[关键事件]")
                || text.startsWith("[当前任务]");
        if (!looksLikeInjectedPrefix) {
            return text;
        }

        int splitIndex = text.lastIndexOf('\n');
        if (splitIndex < 0 || splitIndex >= text.length() - 1) {
            return text;
        }

        String tail = text.substring(splitIndex + 1).trim();
        return tail.isEmpty() ? text : tail;
    }

    /**
     * 截断过长的事件文本。
     *
     * <p>这一步的核心思想是：框架层 Session 保留“够用信息”，
     * 而不是无上限保存完整原文。否则像日志、配置文件、命令输出等大文本，
     * 很容易把 ADK Session 撑得越来越重。
     *
     * <p>案例：
     * <pre>
     *   tool 返回 3000 字符日志
     *   maxLength = 1000
     *
     *   截断结果：
     *   前 1000 字符 + "..."
     * </pre>
     *
     * @param event 原始事件
     * @param maxLength 最大长度
     * @return 截断后的事件
     */
    private Event truncateEventText(Event event, int maxLength) {
        Content content = event.content().orElse(null);
        if (content == null || content.parts().isEmpty()) {
            return event;
        }

        boolean changed = false;
        List<Part> parts = new ArrayList<>(content.parts().orElse(List.of()).size());
        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent() && part.text().get().length() > maxLength) {
                parts.add(part.toBuilder()
                        .text(part.text().get().substring(0, maxLength) + "...")
                        .build());
                changed = true;
            } else {
                // FunctionCall、FunctionResponse、媒体等结构化 Part 原样保留。
                parts.add(part);
            }
        }

        if (!changed) {
            return event;
        }

        Event truncated = event.toBuilder().build();
        truncated.setContent(content.toBuilder().parts(parts).build());
        return truncated;
    }

    /**
     * 只截断 FunctionResponse.response 中的长字符串，保留调用 id、name 和 Part 类型。
     */
    private Event truncateFunctionResponseOutput(Event event, int maxLength) {
        Content content = event.content().orElse(null);
        if (content == null || content.parts().isEmpty()) {
            return event;
        }

        boolean changed = false;
        List<Part> parts = new ArrayList<>(content.parts().orElse(List.of()).size());
        for (Part part : content.parts().orElse(List.of())) {
            if (part.functionResponse().isEmpty()) {
                parts.add(part);
                continue;
            }

            FunctionResponse response = part.functionResponse().get();
            Map<String, Object> values = response.response().orElse(Map.of());
            Map<String, Object> truncatedValues = new LinkedHashMap<>(values);
            boolean responseChanged = false;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getValue() instanceof String text && text.length() > maxLength) {
                    truncatedValues.put(entry.getKey(), text.substring(0, maxLength) + "...");
                    changed = true;
                    responseChanged = true;
                }
            }

            if (responseChanged) {
                FunctionResponse truncatedResponse = response.toBuilder()
                        .response(truncatedValues)
                        .build();
                parts.add(part.toBuilder().functionResponse(truncatedResponse).build());
            } else {
                parts.add(part);
            }
        }

        if (!changed) {
            return event;
        }

        Event truncated = event.toBuilder().build();
        truncated.setContent(content.toBuilder().parts(parts).build());
        return truncated;
    }

    /**
     * 合并 event.actions.stateDelta 到 SessionSnapshot.state。
     *
     * <p>这里不重复保存整段执行历史，而是只同步“最新状态变化”，
     * 让 Session 维持一个轻量的当前运行态。
     *
     * @param snapshot 会话快照
     * @param event 当前事件
     */
    private void mergeStateDelta(SessionSnapshot snapshot, Event event) {
        if (event.actions() == null || event.actions().stateDelta() == null || event.actions().stateDelta().isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : event.actions().stateDelta().entrySet()) {
            if (State.REMOVED.equals(entry.getValue())) {
                snapshot.getState().remove(entry.getKey());
            } else {
                snapshot.getState().put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 裁剪事件列表。
     *
     * <p>先按轮次裁剪，再按总条数软上限裁剪。
     * 这样比单纯“超过 20 条删最前面”更符合对话语义。
     *
     * @param events 事件列表
     */
    private void trimEvents(List<Event> events) {
        trimByTurn(events);

        /*
         * MAX_EVENTS 是软上限。只允许按完整用户轮次删除，不能从一轮中间硬切，
         * 否则可能留下孤立的 FunctionCall 或 FunctionResponse。
         */
        while (events.size() > MAX_EVENTS) {
            int nextTurnStart = findNextUserTurnStart(events);
            if (nextTurnStart <= 0) {
                // 当前只剩一个超长轮次，协议完整性优先于条数硬限制。
                break;
            }
            events.subList(0, nextTurnStart).clear();
        }
    }

    /** 返回第二个真实用户输入的下标，用于一次删除完整的最早一轮。 */
    private int findNextUserTurnStart(List<Event> events) {
        int userTurns = 0;
        for (int i = 0; i < events.size(); i++) {
            if (isActualUserMessage(events.get(i))) {
                userTurns++;
                if (userTurns == 2) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 按用户轮次裁剪事件。
     *
     * <p>算法思路：
     * 从后往前遍历事件，持续回收最近消息；
     * 每遇到一条 user 消息，视为进入上一轮；
     * 当累计到 MAX_TURNS 轮后停止。
     *
     * <p>案例：
     * <pre>
     *   原始事件：
     *   U1 A1 U2 A2 U3 A3 U4 A4 U5 A5
     *
     *   MAX_TURNS = 4
     *
     *   从后往前回收：
     *   U5 A5 U4 A4 U3 A3 U2 A2
     *
     *   最终保留：
     *   U2 A2 U3 A3 U4 A4 U5 A5
     * </pre>
     *
     * <p>这样做的优势是，保留下来的往往是更完整的最近几轮对话，
     * 而不是被消息条数硬裁成东一块西一块的碎片。
     *
     * @param events 原始事件列表
     */
    private void trimByTurn(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }

        LinkedList<Event> trimmed = new LinkedList<>();
        int userTurnCount = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event current = events.get(i);
            trimmed.addFirst(current);
            if (isActualUserMessage(current)) {
                userTurnCount++;
                if (userTurnCount >= MAX_TURNS) {
                    break;
                }
            }
        }

        events.clear();
        events.addAll(trimmed);
    }

    /** 是否为用户真正输入的消息，不包含 ADK 使用 role=user 表示的工具响应。 */
    private boolean isActualUserMessage(Event event) {
        return "user".equalsIgnoreCase(resolveRole(event)) && !hasFunctionResponse(event);
    }

    /**
     * 按 Part 类型识别 FunctionResponse，不能只检查 role。
     * ADK 通常把工具响应包装在 role=user 的 Content 中，但它不代表新的用户轮次。
     */
    private boolean hasFunctionResponse(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .orElse(List.of())
                .stream()
                .anyMatch(part -> part.functionResponse().isPresent());
    }

    /**
     * 解析事件更新时间。
     *
     * @param event 当前事件
     * @return 更新时间
     */
    private Instant resolveUpdateTime(Event event) {
        if (event.timestamp() > 0) {
            return Instant.ofEpochMilli(event.timestamp());
        }
        return Instant.now();
    }
}
