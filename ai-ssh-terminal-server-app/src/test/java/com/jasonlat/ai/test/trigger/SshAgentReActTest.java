package com.jasonlat.ai.test.trigger;


import com.jasonlat.ai.cases.IAIAgentReActServiceCase;
import com.jasonlat.ai.domain.agent.service.IChatService;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.impl.SshExecuteAdkTool;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.jasonlat.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.jasonlat.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.jasonlat.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.jasonlat.ai.domain.ssh.service.ISshConnectionService;
import com.jasonlat.ai.domain.ssh.service.ISshTerminalService;
import com.jasonlat.ai.trigger.api.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.io.InputStream;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SSH Agent ReAct 真实链路测试
 * <p>
 * 与 SshAgentMvpTest 类连接 SSH 服务器，但测试 ReAct 执行模型。
 * <p>
 * ReAct 模式：AI 思考 → 调用工具（executeCommand）→ 分析结果 → 继续思考 → ...
 * 直至给出最终回答。
 * <p>
 * 使用前请修改下方 SSH_SERVER 配置为你自己的服务器信息。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class SshAgentReActTest {

    // ==================== 配置区：从 ssh-config.local 读取 ====================
    // 使用方法：复制 src/test/resources/ssh-config.local 为 ssh-config.local_bak，填入你的服务器信息
    private static final Properties SSH_CONFIG = loadSshConfig();
    private static final String SSH_HOST = SSH_CONFIG.getProperty("ssh.host");
    private static final int SSH_PORT = Integer.parseInt(SSH_CONFIG.getProperty("ssh.port", "22"));
    private static final String SSH_USERNAME = SSH_CONFIG.getProperty("ssh.username");
    private static final String SSH_PASSWORD = SSH_CONFIG.getProperty("ssh.password");

    // Agent 配置（对应 only-one-agent.yml 中的 agent-id）
    private static final String AGENT_ID = "100000";
    private static final String USER_ID = "react-jasonlat";
    // =================================================================

    /**
     * 从 classpath:ssh-config.local 读取 SSH 连接配置
     * <p>
     * 该文件不提交到 Git（已在 .gitignore 中排除），使用前请从 ssh-config.local_bak 复制并填写真实信息。
     */
    private static Properties loadSshConfig() {
        Properties props = new Properties();
        try (InputStream is = SshAgentReActTest.class.getClassLoader().getResourceAsStream("ssh-config.local_bak")) {
            if (is == null) {
                throw new RuntimeException(
                        "未找到 ssh-config.local 文件！请复制 src/test/resources/ssh-config.local 为 ssh-config.local_bak 并填写服务器信息。");
            }
            props.load(is);
        } catch (Exception e) {
            throw new RuntimeException("加载 ssh-config.local_bak 失败: " + e.getMessage(), e);
        }
        return props;
    }

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

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
        log.info("========== ReAct Test 初始化开始 ==========");

        // 1. 创建 SSH 连接记录
        SshConnectionEntity connEntity = SshConnectionEntity.builder()
                .connectionName("ReAct测试连接")
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
            throw new RuntimeException("SSH 连接失败/port/账号/密码");
        }
        log.info("2. SSH 连接成功 host={}:{}", SSH_HOST, SSH_PORT);

        // 3. 打开终端会话
        TerminalSessionEntity terminal = sshTerminalService.openTerminal(connectionId, 120, 24);
        terminalSessionId = terminal.getSessionId();
        log.info("3. 终端打开 terminalSessionId={}", terminalSessionId);

        // 4. 创建 AI 对话会话（ADK Runner 需要 sessionId 管理对话状态）
        chatSessionId = chatService.createSession(AGENT_ID, USER_ID);
        log.info("4. AI 对话会话已创建 chatSessionId={}", chatSessionId);

        // 5. 绑定终端会话到 ThreadLocal（核心！executeCommand 工具从这里取 terminalSessionId）
        SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
        log.info("5. ThreadLocal 已绑定终端会话");

        log.info("========== ReAct Test 初始化完成，可以开始对话了 ==========\n");
    }

    /**
     * 交互式 ReAct 对话：在控制台输入自然语言，AI Agent 通过 ReAct 模式执行命令并返回结果
     * <p>
     * 使用同步 chat() 方法，等待 ReAct 循环完成后返回最终结果。
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

                // 构建 ReAct 请求
                ChatRequest requestDTO = new ChatRequest();
                requestDTO.setAgentId(AGENT_ID);
                requestDTO.setUserId(USER_ID);
                requestDTO.setSessionId(chatSessionId);
                requestDTO.setMessage(input);
                requestDTO.setTerminalSessionId(terminalSessionId);

                System.out.print("\nAI > ");
                // 同步调用，等待 ReAct 循环完成
                String result = reactServiceCase.chat(requestDTO);
                System.out.println(result);

            } catch (Exception e) {
                log.error("对话异常", e);
                System.out.println("出错: " + e.getMessage());
            }
        }

        scanner.close();
    }

    /**
     * 单次 ReAct 对话测试：不交互，直接执行一条指令看结果
     * <p>
     * 使用同步 chat() 方法。
     */
    @Test
    public void test_singleChat() {
        SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);

        String message = "查看服务器系统信息，包括操作系统版本、CPU、内存";
        log.info("发送消息: {}", message);

        ChatRequest requestDTO = new ChatRequest();
        requestDTO.setAgentId(AGENT_ID);
        requestDTO.setUserId(USER_ID);
        requestDTO.setSessionId(chatSessionId);
        requestDTO.setMessage(message);
        requestDTO.setTerminalSessionId(terminalSessionId);

        System.out.print("\nAI > ");
        String result = reactServiceCase.chat(requestDTO);
        System.out.println(result);
    }

    /**
     * 流式 ReAct 对话测试：通过 SSE 接收逐步输出
     * <p>
     * 使用 chatStream() 方法，实时展示 ReAct 各阶段事件（文本、工具调用、工具结果）。
     */
    @Test
    public void test_streamChat() throws Exception {
        SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);

        String message = "检查 docker 是否安装，如果没有安装请帮我安装";
        log.info("发送消息: {}", message);

        ChatRequest requestDTO = new ChatRequest();
        requestDTO.setAgentId(AGENT_ID);
        requestDTO.setUserId(USER_ID);
        requestDTO.setSessionId(chatSessionId);
        requestDTO.setMessage(message);
        requestDTO.setTerminalSessionId(terminalSessionId);

        // 调用流式接口，获取 SSE Emitter
        ResponseBodyEmitter emitter = reactServiceCase.chatStream(requestDTO);

        // 使用 CountDownLatch 等待异步流完成
        CountDownLatch latch = new CountDownLatch(1);

        emitter.onCompletion(() -> {
            log.info("SSE 流完成");
            latch.countDown();
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 流超时");
            latch.countDown();
        });

        emitter.onError(throwable -> {
            log.error("SSE 流错误", throwable);
            latch.countDown();
        });

        // 等待 ReAct 循环完成（最多 3 分钟）
        boolean completed = latch.await(3, TimeUnit.MINUTES);

        if (!completed) {
            log.warn("等待超时，ReAct 可能未完成");
        }

        log.info("流式对话结束");
    }

    /**
     * 直接测试 SSH 命令执行（不经过 AI，验证 SSH 链路是否通）
     */
    @Test
    public void test_directCommand() throws InterruptedException {
        log.info("直接执行 SSH 命令测试（不经过 AI）");

        String[] commands = {
                "whoami",
                "hostname",
                "uname -a",
                "df -h",
                "free -m"
        };

        for (String cmd : commands) {
            System.out.println("\n--- 执行: " + cmd + " ---");
            String output = sshTerminalService.executeCommand(terminalSessionId, cmd);
            System.out.println(output);
        }
    }

}
