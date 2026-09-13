package com.jasonlat.ai.domain.ssh.adapter.port;

import com.jasonlat.ai.domain.ssh.model.valobj.TerminalReadResult;

import java.util.concurrent.CompletableFuture;

/**
 * 终端会话服务接口
 * 负责管理 SSH 终端会话，包括打开/写入/读取/调整大小/关闭会话
 */
public interface ITerminalSessionPort {

    /**
     * 打开终端会话
     *
     * @param connectionId SSH连接ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return 会话ID
     */
    String openTerminal(String connectionId, int cols, int rows);

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
     * 关闭终端会话
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

}
