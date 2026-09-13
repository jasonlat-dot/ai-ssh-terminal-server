package com.jasonlat.ai.test.trigger;


import com.google.adk.events.Event;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl.SshExecuteAdkTool;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.Scanner;

/**
 * SSH Agent MVP 测试
 * <p>
 * 最小可用版本：启动后自动连接服务器，在控制台输入自然语言指令，
 * AI Agent 自动调用 executeCommand 工具执行 SSH 命令，并返回分析结果。
 * <p>
 * 使用前请修改下方 SSH_SERVER 配置为你自己的服务器信息。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class SshAgentMvpTest {

    // ==================== 配置区：改成你自己的服务器 ==================== https://618.gaga.plus
    private static final String SSH_HOST = "192.168.3.16";
    private static final int SSH_PORT = 22;
    private static final String SSH_USERNAME = "root";
    private static final String SSH_PASSWORD = "lijiaqiang12";

    // Agent 配置（对应 only-one-agent.yml 中的 agent-id）
    private static final String AGENT_ID = "100000";
    private static final String USER_ID = "mvp-jasonlat";
    // =================================================================

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private IChatService chatService;

    /** 连接ID（createConnection 后自动生成） */
    private String connectionId;
    /** 终端会话ID（openTerminal 后自动生成） */
    private String terminalSessionId;
    /** 对话会话ID（createSession 后自动生成） */
    private String chatSessionId;

    /**
     * 初始化：创建连接 → 建立SSH → 打开终端 → 创建对话会话 → 绑定ThreadLocal
     */
    @Before
    public void init() {
        log.info("========== MVP 初始化开始 ==========");

        // 1. 创建 SSH 连接记录
        SshConnectionEntity connEntity = SshConnectionEntity.builder()
                .connectionName("MVP测试连接")
                .host(SSH_HOST)
                .port(SSH_PORT)
                .username(SSH_USERNAME)
                .authType(AuthTypeEnum.PASSWORD)
                .password(SSH_PASSWORD)
                .userId(USER_ID)
                .build();

        SshConnectionConfigEntity configEntity = SshConnectionConfigEntity.builder()
                .connectTimeout(15)
                .keepaliveInterval(30)
                .build();

        sshConnectionService.createConnection(connEntity, configEntity);
        connectionId = connEntity.getConnectionId();
        log.info("1. 连接记录创建成功 connectionId={}", connectionId);

        // 2. 建立 SSH 连接
        boolean connected = sshConnectionService.connect(connectionId);
        if (!connected) {
            throw new RuntimeException("SSH 连接失败，请检查 host/port/账号/密码");
        }
        log.info("2. SSH 连接成功 host={}:{}", SSH_HOST, SSH_PORT);

        // 3. 打开终端会话
        TerminalSessionEntity terminal = sshTerminalService.openTerminal(connectionId, 120, 24);
        terminalSessionId = terminal.getSessionId();
        log.info("3. 终端会话已打开 terminalSessionId={}", terminalSessionId);

        // 4. 创建 AI 对话会话
        chatSessionId = chatService.createSession(AGENT_ID, USER_ID);
        log.info("4. AI 对话会话已创建 chatSessionId={}", chatSessionId);

        // 5. 绑定终端会话到 ThreadLocal（核心！executeCommand 工具从这里取 terminalSessionId）
        SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
        log.info("5. ThreadLocal 已绑定终端会话");

        log.info("========== MVP 初始化完成，可以开始对话了 ==========\n");
    }

    /**
     * 交互式对话：在控制台输入自然语言，AI Agent 执行命令并返回结果
     * <p>
     * 运行后在控制台输入：
     * - "查看服务器系统信息"
     * - "检查 docker 是否安装"
     * - "查看磁盘和内存使用情况"
     * - "exit" 退出
     */
    @Test
    public void test_ai_shell() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\n你 > ");
            String input = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("再见！");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            try {
                // 每次对话前重新绑定 ThreadLocal（防止异步线程丢失）
                SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);

                System.out.print("AI > ");
                Flowable<Event> events = chatService.handleMessageStream(
                        AGENT_ID, USER_ID, chatSessionId, input, terminalSessionId);

                StringBuilder fullContent = new StringBuilder();
                events.blockingForEach(event -> {
                    String text = event.stringifyContent();
                    if (!text.isEmpty()) {
                        System.out.print(text);
                        fullContent.append(text);
                    }
                });
                System.out.println(); // 换行

            } catch (Exception e) {
                log.error("对话异常", e);
                System.out.println("出错: " + e.getMessage());
            }
        }

        scanner.close();
    }

    /**
     * 单次对话测试：不交互，直接执行一条指令看结果
     */
    @Test
    public void test_singleChat() throws InterruptedException {
        SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);

        String message = "使用docker ps查看服务器docker有哪些容器在运行";
        log.info("发送消息: {}", message);

        Flowable<Event> events = chatService.handleMessageStream(
                AGENT_ID, USER_ID, chatSessionId, message, terminalSessionId);

        System.out.print("\nAI > ");
        events.blockingForEach(event -> {
            String text = event.stringifyContent();
            if (!text.isEmpty()) {
                System.out.print(text);
            }
        });
        System.out.println();
        Thread.sleep(100000);
    }

    /**
     * 直接测试 SSH 命令执行（不经过 AI，验证 SSH 链路是否通）
     */
    @Test
    public void test_directCommand() throws InterruptedException {
        log.info("直接执行 SSH 命令测试（不经过 AI）");

        String[] commands = {
                "df -h\r",
        };

        for (String cmd : commands) {
            System.out.println("\n--- 执行: " + cmd + " ---");
            String output = sshTerminalService.executeCommand(terminalSessionId, cmd);
            System.out.println(output);
        }
    }

}
