package com.jasonlat.ai.domain.ssh.adapter.port;

import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;
import com.jasonlat.ai.domain.ssh.model.valobj.TerminalTermination;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 终端会话服务接口。
 * <p>
 * 每次 {@link #openTerminal(String, String, int, int)} 都必须生成新的 terminalSessionId，并在
 * connectionId 对应的共享 SSH Session 上打开独立 ChannelShell。读写、尺寸、Reader、
 * Long Poll 和关闭操作都以 terminalSessionId 隔离，因此多个窗口连接同一服务器时不会
 * 互相消费输出或关闭对方的终端。
 */
public interface ITerminalSessionPort {

    /**
     * 在指定底层 SSH 连接上打开一个新的独立终端会话；重复调用不会复用旧终端。
     *
     * @param userId       连接所属用户 ID
     * @param connectionId SSH连接ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return 会话ID
     */
    String openTerminal(String userId, String connectionId, int cols, int rows);

    /**
     * 写入命令到终端
     *
     * @param sessionId 会话ID
     * @param command   命令内容
     */
    void write(String sessionId, String command);

    /**
     * 在当前交互式 Shell 中执行一条 Agent 命令，并等待独立命令缓冲区收集完整结果。
     *
     * <p>该方法与 {@link #write(String, String)} 的区别：</p>
     * <ul>
     *   <li>write 只负责发送按键或文本，不等待输出；</li>
     *   <li>executeCommand 会添加唯一边界、等待命令结束，并返回这条命令的完整输出。</li>
     * </ul>
     *
     * <p>Agent 使用独立捕获缓冲区，不会调用或消费前端 Long Poll 使用的
     * {@link #readAsync(String)} 输出缓冲区。</p>
     *
     * @param sessionId     终端会话 ID
     * @param command       Shell 命令
     * @param timeoutSeconds 命令总超时时间（秒）
     * @return 完整命令输出
     * @throws InterruptedException 等待结果的 Agent 线程被中断
     */
    String executeCommand(String sessionId, String command, long timeoutSeconds) throws InterruptedException;

    /**
     * 读取终端输出
     *
     * @param sessionId 会话ID
     * @return 终端输出内容
     */
    String read(String sessionId);

    /**
     * 异步读取 SSH Terminal 数据。
     * 如果当前已经有数据：Future 立即完成。
     * 如果当前没有数据： Future 暂时挂起。
     * SSH Reader 收到数据后：Future 自动完成。
     * HTTP Long Poll 超时由 Controller 控制。
     */
    CompletableFuture<TerminalReadResult> readAsync(String sessionId);

    /**
     * 调整终端大小
     *
     * @param sessionId 会话ID
     * @param cols      新的列数
     * @param rows      新的行数
     */
    void resize(String sessionId, int cols, int rows);

    /**
     * 只关闭指定 terminalSessionId 对应的 Shell Channel，不释放共享的底层 SSH Session。
     *
     * @param sessionId 会话ID
     */
    void closeSession(String sessionId);

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean sessionExists(String sessionId);

    /** 查询短期保留的终端结束原因；记录过期或从未存在时返回 null。 */
    TerminalTermination getTermination(String sessionId);

    /**
     * 判断某个连接配置下是否仍有活动终端。
     * 多窗口会为同一个 connectionId 创建多个独立 Shell Channel；关闭其中一个窗口时，
     * 只有最后一个终端也关闭后，底层 SSH Session 才允许被释放。
     *
     * @param connectionId SSH 连接 ID
     * @return 是否至少存在一个活动终端
     */
    boolean hasActiveSessions(String connectionId);

    /**
     * 清理 Channel 已断开、Reader 已退出或超过空闲时间的终端。
     *
     * @return 本次实际清理的 terminalSessionId
     */
    List<String> cleanupInactiveSessions();

}
