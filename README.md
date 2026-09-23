# AI SSH Terminal Server：从页面到代码的入门指南

这是一个“网页 SSH 终端 + AI 运维助手”后端。用户可以在左侧操作真实 SSH 终端，也可以在右侧向 Agent 提问；Agent 会按配置将任务派发给子 Agent，由子 Agent 调用 SSH 工具执行命令，最后把过程与结果返回页面。

本文以当前仓库代码为准，重点回答两个问题：**终端文字怎样到达前端？一次 Agent 对话怎样派发、执行并返回？** 配套前端位于同级目录 `../ai-ssh-terminal-client`。

> 架构图：[在 draw.io / diagrams.net 中打开六页可编辑架构图](docs/architecture.drawio)。01～04 页分别是系统总览、终端长轮询、Agent 派发与事件、历史与停止；05 页把前端、Trigger、Case、上下文、ADK 与外部资源放进一张完整系统架构图；06 页沿一次请求展开历史裁剪、里程碑、Provider、Prompt 和结果回写。建议先看 05 建立全局认识，再用 02、03、06 跟具体调用链；具体行为以本文及源码为准。

阅读顺序建议：先看“四种 ID” → 跟终端 `read` → 跟 Agent `chat_stream` → 跟子 Agent 派发 → 最后看上下文和停止。下文的“代码导航与断点实验”给出可照着操作的路线。

## 先认清项目

| 位置 | 新人先看什么 |
| --- | --- |
| `ai-ssh-terminal-server-app` | `Application.java` 启动入口、`src/main/resources` 配置与测试 |
| `ai-ssh-terminal-server-trigger` | HTTP Controller：接收前端请求、返回 JSON 或流式事件 |
| `ai-ssh-terminal-server-case` | 一次 Agent 请求的编排节点、事件转发、停止控制 |
| `ai-ssh-terminal-server-domain` | Agent 装配、会话/上下文、派发、SSH 工具和业务服务 |
| `ai-ssh-terminal-server-infrastructure` | SSH 会话、终端读写、数据库等具体实现 |
| `ai-ssh-terminal-server-api` / `ai-ssh-terminal-server-types` | 接口 DTO、通用类型 |

根 `pom.xml` 当前使用 Java 17、Spring Boot 3.4.3、Spring AI 1.1.5 和 Google ADK 1.2.0。建议先从 Controller 跟一次请求，暂时不用从 ADK 源码或所有配置类开始读。

### 本地启动前要核对

1. 使用 JDK 17 和 Maven，启动 `ai-ssh-terminal-server-app` 中的 `com.jasonlat.ai.Application`；前端在同级 `ai-ssh-terminal-client` 安装依赖后运行 `npm run dev`。
2. 根 `application.yml` 默认激活 `sit`。仓库中有 `application-dev.yml`、`application-prod.yml`，但没有 `application-sit.yml`；本地调试应**明确选择并检查实际激活的 profile**，不要默认认为加载了 SSH Agent 配置。
3. `dev` 导入 `agent/only-agent.yml`，`prod` 导入 `agent/ssh-agent.yml`。若要调试本文的主/子 Agent 路线，确认实际导入了 `ssh-agent.yml`。启动还依赖可访问的数据库、SSH 连接配置以及可用的模型服务；API 密钥、代理和 SSH 凭据请在本地安全配置，不要写进文档或提交新密钥。
4. 当前 `dev` 设置了 servlet context path `/api/v1`，`prod` 没设置；同时 SSH Controller 自身映射已经包含 `/api/v1/ssh`。因此完整 URL = **context path + Controller 路径**，`dev` 下可能出现 `/api/v1/api/v1/ssh/...`。前端 `src/api/ssh.ts` 与 `src/api/agent.ts` 的默认 base URL 是按无 context path 的后端写的；联调时先在浏览器 Network 核对实际 URL，并调整前端环境变量 `VITE_SSH_API_BASE_URL`、`VITE_AGENT_API_BASE_URL`。

### 四种 ID，别混用

| ID | 含义 | 用在哪里 |
| --- | --- | --- |
| `connectionId` | 保存的 SSH 连接记录 | 选择主机、`connect`、打开终端 |
| `terminalSessionId` | 一次交互式 SSH Shell 会话 | `/terminal/read`、`/terminal/write`、Agent 工具定位真实终端 |
| Agent `sessionId` | 一段 AI 对话/历史记录 | `chat_stream`、查询历史、停止对话、子 Agent 事件关联 |
| 前端本地 tab ID | 页面上一个终端标签 | 仅用于 UI 状态；不是后端终端或 Agent 会话 ID |

前端发 Agent 消息时会把 **Agent `sessionId`** 和当前已连接终端的 **`terminalSessionId`** 一起传给后端。`userId` 用于会话归属校验，也需要前后保持一致。

## 路线一：前端如何“轮询”取得终端输出

这里用的是**长轮询**，不是每隔固定时间查一次数据库，也不是 Agent 的 SSE。浏览器通常挂着一个 `/terminal/read` 请求；有输出时后端立即返回，没有输出时最多等待约 25 秒，然后前端立刻发下一次。前端请求超时配置约 60 秒，大于后端长轮询等待时间。

```text
选择主机
  → POST /api/v1/ssh/connect
  → POST /api/v1/ssh/terminal/open → terminalSessionId、initialOutput
  → RemoteTerminal.subscribe() → poll()
  → GET /api/v1/ssh/terminal/read?sessionId=terminalSessionId（长轮询）
  → SSH Reader 读到字节，放入 outputBuffer 并唤醒等待中的 read
  → read 返回 DATA → RemoteTerminal.emit() → xterm.write() 显示
  → 再发下一次 read
```

跟代码时按以下顺序下断点：

1. 前端 `src/App.tsx` 的主机连接操作，接着看 `src/api/ssh.ts`、`src/api/terminal.ts` 的 `connect/open`，确认拿到的 `terminalSessionId`。
2. 前端 `src/state/remoteTerminal.ts` 的 `subscribe`、`schedule`、`poll`、`emit`。`polling` 标记保证同一个前端实例不会并发发多条 read；`src/components/RemoteTerminalView.tsx` 把输出写入 xterm。
3. 后端 `SshTerminalController.readAsyncFromTerminal` → `SshTerminalService.readTerminalAsync` → `TerminalSessionPort.readAsync`。缓冲区已有文字时直接返回；没有时保存待完成的 `CompletableFuture`。同一会话的新 read 会把旧 read 标成 `REPLACED`。
4. 另一条线程在 `TerminalSessionPortSupport.runOutputReader` 阻塞读取 SSH 输入流。读到内容后 `appendOutput` 写缓冲并完成等待中的 future，Controller 才把结果返回给浏览器。这是定位“SSH 有输出但前端没显示”的关键断点。

前端对返回状态的处理：`DATA` 显示内容并继续读；`TIMEOUT` 是正常空等，继续读；`REPLACED` 也重新读；`DISCONNECTED`、`READER_ERROR` 停止轮询并提示重新连接。用户在终端键入内容则走另一方向：xterm → `RemoteTerminal.write/exec` → `POST /terminal/write` → SSH Shell。命令输出仍由同一个长轮询读回来；前端**没有**调用 Controller 中映射已注释的 `/terminal/exec`。

Agent 工具执行命令时也使用此终端，但工具通过独立的命令输出捕获机制取结果；终端展示仍由 SSH Reader + 长轮询完成，两者不是两个线程争抢读取同一个 SSH 输入流。`TerminalSessionPort` 会串行化同一 Shell 的 Agent 命令，当前工具命令等待上限与 25 秒长轮询等待上限也不是同一个超时。

## 路线二：Agent 对话、子 Agent 和工具

页面右侧的对话**不是轮询**。`src/api/agent.ts` 的 `chatStream` 发送一次 `POST /agent/chat_stream`，持续读取响应体，并按行解析流式 JSON 事件；文本、工具状态和子 Agent 状态逐步显示。需要查看旧对话时，前端另外调用 `query_session_list` 和 `query_message_list`，这两个是普通查询接口，不是聊天流。

```text
App.sendMessage() → POST /agent/chat_stream
  → AgentController.chatStream()
  → AIAgentReActServiceCase.chatStream()：注册本次流、异步执行
  → RootNode：确认 ID、加载历史和业务上下文
  → AiCallNode：裁剪历史、准备 ADK Session、runner.runAsync()
      → 主 Agent 的模型决定回复或调用派发工具
      → 派发工具启动子 Runner，子 Agent 可调用 executeCommand
      → SSH 工具执行命令，结果回到子 Agent，子结果再回到主 Agent
  → ToolCallNode（有工具事件才经过）：核对、归档已执行的结果
  → LoopDecisionNode → UserFeedbackNode：确定结束状态、保存上下文、发 done
```

`AiCallNode` 里调用 `runner.runAsync()` 后，**ADK 在内部完成模型 ↔ 工具的 ReAct 循环**。Case 层的 `ToolCallNode` 不会再次执行工具，`LoopDecisionNode` 也不会为了工具结果再手工重跑 `AiCallNode`。如果看到一条请求中多次 LLM 调用，先区分是 ADK 正常的“调用工具后再向模型总结”，还是异常重复执行。

### 启动时，主/子 Agent 如何形成

`application-prod.yml` 导入 `agent/ssh-agent.yml`。应用启动后装配链会读取配置并创建模型、Agent、工具与 Runner。配置中的 `sshOperator` 是主 Agent，`sshDiagnosisAgent`、`sshChangeAgent` 是它的两个子 Agent；装配时 Agent 名称带上应用名前缀，例如 `sshAgent_sshOperator`。界面选择的 `agentId`（配置中为 `100000`）用于找到整组配置，**不等于** ADK Agent 的内部名称。

关键点在 `domain/.../amory/node/AgentToolNode.java`：它会为具有 `sub-agents` 的主 Agent 重新配置工具。当前主 Agent 主要持有单个子 Agent 派发工具、批量派发 `dispatchSubAgents` 和规划派发 `planAndDispatchSubAgents`；**主 Agent 自己不直接持有 `executeCommand`**。子 Agent 才调用名为 `executeCommand` 的 `SshExecuteAdkTool`；其 ADK 入口方法是 `runAsync`，实际 SSH 逻辑在 `executeForTerminal`。若在主 Agent 的日志中找不到 SSH 工具，先看当前 Agent 名称及 `AgentToolNode`，不一定是工具注册失败。

| 路径 | 做什么 | 适合从哪里追踪 |
| --- | --- | --- |
| 单个子 Agent 派发 | 主 Agent 把一项任务交给诊断或变更 Agent；工具自身创建子 Runner | `SubAgentDispatchTool.runAsync`，**不经过** `SubAgentDispatchService` |
| 批量派发 | 根据模型给出的多项任务执行 | `BatchSubAgentDispatchTool` → `DynamicAgentOrchestrator` → `SubAgentDispatchService` |
| 先规划再派发 | 规划任务，再串行/并行调度子 Agent | `DynamicPlanDispatchTool` → `DynamicAgentOrchestrator` → `SubAgentDispatchService` |
| 子 Agent 执行 SSH | 校验命令，使用本次请求的终端会话执行 | `SshExecuteAdkTool.runAsync` → `executeForTerminal` → `SshTerminalService.executeCommand` → `TerminalSessionPort.executeCommand` |

单个派发在 `SubAgentDispatchTool` 中创建子 Runner/ADK Session；批量或规划派发由 `DynamicAgentOrchestrator` 调度，再在 `SubAgentDispatchService` 中创建子 Runner/ADK Session。两条路径都把父请求的 `terminalSessionId`、业务 Agent `sessionId` 等关联信息带进去。因此父子 Agent 有不同的 ADK 执行会话，却能使用当前请求对应的 SSH 终端。单个终端 Shell 上的 Agent 命令按锁串行；“子 Agent 并行规划”不等于同一 Shell 上的命令可以无序并行写入。

### 为什么页面能看到子 Agent 过程

子 Agent 的执行过程不只等最终工具返回。`AgentEventPublisher` 按**业务 Agent `sessionId`**发布子 Agent/工具事件；Case 层 `AIAgentReActServiceCase` 为正在执行的请求注册监听器，`NestedAgentEventForwarder` 把它们转成同一条 `chat_stream` 上的 `agent_start`、`agent_text`、`agent_result`、`tool_call`、`tool_result` 事件。主 Agent 的文本由 `AiCallNode` 发出，结束时还有 `done`；异常时可能有 `error`。前端通过 `agentCallId` 归组子 Agent 与其工具，通过 `toolCallId` 对应工具开始和结果；不要按事件到达顺序猜测它们属于谁。

如果子 Agent 已完成命令而页面只有最后的主 Agent 总结，沿着“`SubAgentDispatchTool.runAsync` 中的 `.doOnNext` **或** `SubAgentDispatchService` 中的 `.blockingForEach` 收到子事件 → `AgentEventPublisher.publishToSession` → `NestedAgentEventForwarder` → 前端 `chatStream` 解析”这条链检查；如果终端有输出但 Agent 没有工具结果，改查 `SshExecuteAdkTool` 和命令捕获链。

### 历史、上下文与停止

- `ChatService` 管理会话记录和历史查询；`LongTermMemoryService` 将用户、助手、工具消息持久化。`RootNode` 在请求开始时装载历史/业务上下文，`AiCallNode` 裁剪并准备本轮要给 ADK 的内容。`ConversationContextStore` 保存可跨请求复用的业务上下文；`CustomAdkSessionService` 为本轮 ADK 执行投影/准备会话，**不要误以为 ADK Session 是项目唯一或最终的长期历史来源**。
- 点击停止时，前端 `src/App.tsx` 先调用 `POST /agent/stop_chat`，再中止本地流读取。后端用 `agentId + userId + sessionId` 定位活动请求，由 `ReActStreamCancellation` / Agent 取消逻辑向 Runner、子任务及 SSH 命令传递取消。只在浏览器关闭流不等于后端已停止；调试时检查 `/stop_chat` 的返回值以及后端取消日志。

## 新人推荐的一次 Debug 路线

准备一台**允许测试的 SSH 主机**和可用模型，选择实际加载 `ssh-agent.yml` 的配置，再按顺序做：

1. **确认启动装配。** 在 `AiAgentAutoConfig` 与 `AgentToolNode` 下断点，看 `agentId=100000` 是否生成主 Agent、两个子 Agent，主 Agent 的工具列表是否为派发工具。
2. **确认终端长轮询。** 页面连接主机，记下 Network 中 `connect`、`terminal/open`、`terminal/read` 的 URL 与 `terminalSessionId`。在左侧终端输入 `pwd`。用上一节“路线一”的断点看到 write → SSH Reader → read 返回 DATA → xterm 展示。
3. **确认 Agent 请求。** 在右侧询问“查看当前目录”，记下 `chat_stream` 请求体中的 `agentId`、`userId`、Agent `sessionId`、`terminalSessionId`；在 `AgentController.chatStream`、`AIAgentReActServiceCase.chatStream`、`RootNode`、`AiCallNode` 下断点，确认四个值没有被混用。
4. **跟一次派发。** 先在父模型的 FunctionCall 看工具名：直接派发停在 `SubAgentDispatchTool`，批量/规划派发才停在 `DynamicAgentOrchestrator`、`SubAgentDispatchService`；两条路线最后都可停在 `SshExecuteAdkTool`。观察子 Runner 如何取得终端 ID、命令结果如何返回，再如何给主 Agent 总结。
5. **看两种输出。** 浏览器 Network 中 `chat_stream` 的 `text/agent_*/tool_*/done` 是 AI 事件；左侧终端的 `terminal/read` `DATA` 是 SSH 字节输出。它们可描述同一次命令，但来源、协议和生命周期不同。
6. **验证历史与停止。** 发送完消息查看 `query_session_list`、`query_message_list`；新开一轮确认历史仍在。再用可安全中断的测试任务验证停止按钮，检查 `/agent/stop_chat` 是否确实找到活动请求，不能只看前端显示“已停止”。

调试日志可先用 `sessionId` 贯穿 `ReAct链路`、子 Agent 事件，用 `terminalSessionId` 贯穿 SSH 工具和 Terminal Reader。日志里同名“session”不一定是同一类 ID。

## 常用 HTTP 入口

以下均是 **Controller 映射**，实际请求前还要加当前 profile 的 servlet context path。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /agent/query_ai_agent_config_list` | 页面获取 Agent 列表 |
| `POST /agent/create_session` | 创建 Agent 对话会话 |
| `GET /agent/query_session_list`、`GET /agent/query_message_list` | 历史会话与消息 |
| `POST /agent/chat_stream` | 流式 Agent 事件；不是轮询 |
| `POST /agent/stop_chat` | 请求后端停止本轮执行 |
| `POST /api/v1/ssh/connect` | 建立已保存的 SSH 连接 |
| `POST /api/v1/ssh/terminal/open` | 创建交互式终端会话 |
| `GET /api/v1/ssh/terminal/read` | 终端输出长轮询 |
| `POST /api/v1/ssh/terminal/write`、`/resize`、`/close` | 输入、调整尺寸、关闭终端 |

接口返回体和字段请以 `trigger` 层 Controller、`api` 层 DTO 以及前端 `src/api` 中的当前定义为准；不要拿旧脚手架示例的 Agent ID 或 URL 直接请求这个项目。

## 常见问题先查哪里

- **页面连不上接口：** 检查 profile、端口、context path、前端两个 API base URL；尤其确认有没有重复的 `/api/v1`。
- **Agent 列表为空：** 检查实际导入的 Agent YAML、应用启动时的装配日志与 `AgentToolNode`，并确认数据库/模型配置没有令启动失败。
- **终端有命令却不刷新：** 查看 `/terminal/read` 是否仍在请求、返回状态是什么，再检查 `TerminalSessionPortSupport` Reader 和 `TerminalSessionPort` 缓冲/future。`TIMEOUT` 不是故障，`READER_ERROR` 和 `DISCONNECTED` 才需要重连。
- **工具拿不到终端：** 对比请求中的 `terminalSessionId` 与子 Runner 初始状态传入的 ID；不要拿 Agent `sessionId` 当终端 ID。
- **工具执行了但没有子 Agent 文本：** 分开排查工具响应和子 Agent 事件转发链，不要把 `/terminal/read` 当成 `chat_stream` 的文本来源。
- **点击停止后 SSH 还在跑：** 确认 `/agent/stop_chat` 返回确实命中活动请求，再沿 Case 取消 → 子任务取消 → SSH 命令中断检查；浏览器 `AbortController` 单独生效并不能证明后端已停。

## 把各层连接起来：代码导航

上面的路线是“做什么”，本节列出“具体在哪个文件”。同名 `RootNode` 不止一个：Agent 启动装配和每次对话执行分别有自己的 `RootNode`，下断点时尤其要看包名。

| 场景 | 先打开的源码 |
| --- | --- |
| 应用启动和 Agent 装配 | [Application.java](ai-ssh-terminal-server-app/src/main/java/com/jasonlat/ai/Application.java)、[AiAgentAutoConfig.java](ai-ssh-terminal-server-app/src/main/java/com/jasonlat/ai/config/AiAgentAutoConfig.java)、[ArmoryService.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/ArmoryService.java) |
| HTTP 入口 | [SshConnectionController.java](ai-ssh-terminal-server-trigger/src/main/java/com/jasonlat/ai/trigger/http/SshConnectionController.java)、[SshTerminalController.java](ai-ssh-terminal-server-trigger/src/main/java/com/jasonlat/ai/trigger/http/SshTerminalController.java)、[AgentController.java](ai-ssh-terminal-server-trigger/src/main/java/com/jasonlat/ai/trigger/http/AgentController.java) |
| 终端输出链 | [SshTerminalService.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/ssh/service/terminal/SshTerminalService.java)、[TerminalSessionPort.java](ai-ssh-terminal-server-infrastructure/src/main/java/com/jasonlat/ai/infrastructure/adapter/port/TerminalSessionPort.java)、[TerminalSessionPortSupport.java](ai-ssh-terminal-server-infrastructure/src/main/java/com/jasonlat/ai/infrastructure/adapter/port/TerminalSessionPortSupport.java) |
| Agent Case 入口 | [AIAgentReActServiceCase.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/AIAgentReActServiceCase.java)、[RootNode.java（Case）](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/node/RootNode.java)、[AiCallNode.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/node/AiCallNode.java) |
| Agent 链尾 | [ToolCallNode.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/node/ToolCallNode.java)、[LoopDecisionNode.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/node/LoopDecisionNode.java)、[UserFeedbackNode.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/node/UserFeedbackNode.java) |
| Agent 工具装配 | [AgentNode.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/node/AgentNode.java)、[AgentToolNode.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/node/AgentToolNode.java)、[RunnerNode.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/node/RunnerNode.java) |
| 子 Agent / SSH 工具 | [SubAgentDispatchTool.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/matter/tool/subagents/SubAgentDispatchTool.java)、[DynamicAgentOrchestrator.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/matter/tool/subagents/orchestrator/DynamicAgentOrchestrator.java)、[SubAgentDispatchService.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/matter/tool/subagents/orchestrator/SubAgentDispatchService.java)、[SshExecuteAdkTool.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/matter/tool/ssh/SshExecuteAdkTool.java) |
| 子事件与停止 | [AgentEventPublisher.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/events/AgentEventPublisher.java)、[NestedAgentEventForwarder.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/NestedAgentEventForwarder.java)、[ReActStreamCancellation.java](ai-ssh-terminal-server-case/src/main/java/com/jasonlat/ai/cases/react/model/ReActStreamCancellation.java) |
| 历史和 ADK 会话 | [ConversationContextStore.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/context/cache/ConversationContextStore.java)、[ChatService.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/chat/ChatService.java)、[CustomAdkSessionService.java](ai-ssh-terminal-server-domain/src/main/java/com/jasonlat/ai/domain/agent/service/amory/matter/session/CustomAdkSessionService.java) |
| 前端对应位置 | [App.tsx](../ai-ssh-terminal-client/src/App.tsx)、[remoteTerminal.ts](../ai-ssh-terminal-client/src/state/remoteTerminal.ts)、[terminal.ts](../ai-ssh-terminal-client/src/api/terminal.ts)、[agent.ts](../ai-ssh-terminal-client/src/api/agent.ts) |

### 启动装配链：为什么发请求前 Agent 已经存在

启动时 `AiAgentAutoConfig.onApplicationEvent(ApplicationReadyEvent)` 调用 `ArmoryService.acceptArmoryAgents`。后者先校验配置，再按装配节点依次建立 API、模型、Agent、工具与 Runner。大体顺序是 `amory/RootNode → AiApiNode → ChatModelNode → AgentNode → AgentToolNode → AgentWorkflowNode → RunnerNode`。注意这里的 `amory/RootNode` **不是**上文处理聊天请求的 `cases/react/node/RootNode`。

`AgentNameValidateNode` 为配置中的 Agent 名称加 `app-name` 前缀；例如 `sshAgent.yml` 中的 `sshOperator` 在运行时成为 `sshAgent_sshOperator`。`AgentNode` 先为 LlmAgent 配置 `executeCommand`，然后 `AgentToolNode` 对声明了 `sub-agents` 的父 Agent 重建工具集，并把父 Agent 放回 `agentGroup`。因此调试“主 Agent 没有 SSH 工具”时，不能只停在 `AgentNode` 看半成品，必须继续看到 `AgentToolNode` 后的最终对象。`RunnerNode` 再选定配置中的入口 Agent，组成可被 `AiCallNode` 根据业务 `agentId` 找到的注册结果。

上述配置有三个不同层次：业务 `agentId=100000` 用于 Controller 和注册信息查询；`app-name=sshAgent` 用于 ADK 应用/名称；`sshAgent_sshOperator`、`sshAgent_sshDiagnosisAgent` 等是运行时 Agent 名称。把 `agentId` 当 Agent 名称搜索，常常会误判为“Agent 没装配”。

### 请求地址速查：先看 profile 再拼 URL

| profile | Controller 映射 | 最终常见 URL（端口仍取实际配置） |
| --- | --- | --- |
| `prod`：无 servlet context path | `/agent/chat_stream` | `http://localhost:8888/agent/chat_stream` |
| `prod`：无 servlet context path | `/api/v1/ssh/terminal/read` | `http://localhost:8888/api/v1/ssh/terminal/read` |
| `dev`：context path `/api/v1` | `/agent/chat_stream` | `http://localhost:8888/api/v1/agent/chat_stream` |
| `dev`：context path `/api/v1` | `/api/v1/ssh/terminal/read` | `http://localhost:8888/api/v1/api/v1/ssh/terminal/read` |

这些 URL 是按仓库中映射拼出来的，不代表直接启用某个 profile 就具备完整本地环境：`dev` 当前导入 `only-agent.yml`，`prod` 当前导入 `ssh-agent.yml`，数据库和模型连接仍须在本地核对。若浏览器看到 404，先排除地址拼接，再排查 Controller 业务逻辑。

## 更细地跟一次终端长轮询

终端分为**控制面**（保存连接、打开和关闭）与**数据面**（Shell 输入输出）。首次选择主机时，前端拿 `connectionId` 调 `/api/v1/ssh/connect`；再以它调用 `/terminal/open`，后端创建 Shell 通道，返回 `terminalSessionId` 和 `initialOutput`。前端用这个 ID 构造一个 `RemoteTerminal` 对象，先显示 `initialOutput`，订阅输出后才进入持续 `read`。页面标签切换是前端行为，不应该隐式创建新的 Agent 对话 ID。

`RemoteTerminal.poll` 的一轮可以按下表断点观察。推荐给浏览器 Network 按 `read` 过滤，并在左侧 Shell 输入只读命令 `pwd`：

| 时刻 | 代码与应观察的值 | 结果 |
| --- | --- | --- |
| 请求发出 | `remoteTerminal.ts:poll`、`terminal.ts:read`，查看 `this.sessionId` | 发出 `GET /terminal/read?sessionId=...` |
| 请求抵达 | `SshTerminalController.readAsyncFromTerminal`，查看参数 `sessionId` | ID 应与 `open` 返回的一致 |
| 端口判断 | `TerminalSessionPort.readAsync`，查看 `outputBuffer`、`pendingRead`、`readerFailed`、`eofReached` | 有缓存立即返回 `DATA`；否则挂起一个 future |
| SSH 来字节 | `TerminalSessionPortSupport.runOutputReader` → `appendOutput` | Reader 将输出放入 buffer，唤醒这次 read |
| HTTP 返回 | Controller 的 future 完成，查看 `status/output/hasData/bufferOverflow` | `DATA` 会带此次可消费输出 |
| 页面显示 | `remoteTerminal.ts:emit` → `RemoteTerminalView.tsx` 中 xterm 的 `write` | 文字出现在左侧终端，之后调度下一次 read |

请特别注意三个边界：

1. `TIMEOUT` 是**一次 read 约 25 秒无输出**，前端会马上再发 read；它不是 SSH 命令超时。`REPLACED` 表示该会话出现新 read，旧 pending 请求被替换，也不是命令失败。
2. Reader 是持久读取 SSH 输入流的线程；`AgentCommandCapture` 在 `TerminalSessionPortSupport` 内拿输出副本作为工具结果。若自己新增第二个线程直接读 SSH InputStream，很容易让终端显示与 Agent 工具互相抢输出。
3. 键盘输入不从 read 返回。`RemoteTerminal.write/exec` 向 `/terminal/write` 发字符（提交命令时加 `\r`），Shell 的回显和执行结果随后再走 Reader → `/terminal/read`。虽然前端 API 文件保留了 `terminalApi.exec`，当前页面正常提交命令走的是 `write`，后端 `/terminal/exec` 的 HTTP 映射也被注释了。

`DATA` 可能带 `bufferOverflow=true`，表示输出过快时旧内容曾被丢弃；这时不能把缺失内容归因于 React 渲染。`DISCONNECTED`、`READER_ERROR` 才是终止轮询并提示重新连接的状态。若手动刷新页面、切换标签或重连，记得先核对当前 `terminalSessionId` 是否已经改变。

## 更细地跟一次 Agent 对话

### 第 0 步：页面准备 Agent 和对话会话

页面载入时 `App.tsx` 调 `agentApi.list()`，即 `GET /agent/query_ai_agent_config_list`；它读的是配置中的 Agent 定义，因此返回 `agentId/agentName/agentDesc`，**不是**已连接 SSH 主机列表。选择 Agent 后，页面可查询 `GET /agent/query_session_list` 展示历史；点进一条历史再调 `GET /agent/query_message_list`。这两个查询使用 `agentId + userId`，消息查询还使用 Agent `sessionId`，与终端轮询没有关系。

第一次发言如果前端还没有 Agent `sessionId`，会先调用 `POST /agent/create_session`。`ChatService.createSession` 通过对应 Runner 的 SessionService 创建一个 ID，同时更新会话缓存，并尝试将 `chat_session` 元数据写入数据库。随后页面读取当前终端的 `terminalSessionId`，把两个 ID 一起放进 `chat_stream` 请求。可用以下**字段形状**对照 Network；值均为示意，不要直接复用：

```json
{
  "agentId": "100000",
  "userId": "your-user-id",
  "sessionId": "agent-chat-session-id",
  "terminalSessionId": "opened-ssh-terminal-session-id",
  "message": "查看当前目录"
}
```

`AgentController.chatStream` 检查请求并确认/创建会话，把 `ResponseBodyEmitter` 交给 `AIAgentReActServiceCase`。它把 Content-Type 设为 `text/event-stream`，但当前 Case 主要向响应写入**一行一个 JSON 对象**；前端 `agent.ts:chatStream` 既能解析这种 JSON 行，也兼容标准 SSE `data:` 行。它用 `fetch` + `ReadableStream.getReader()` 增量读取，所以一次聊天通常对应一个持续中的 HTTP 请求，**不会像终端那样重复调用 read 接口**。

### 第 1 步：Case 创建本次请求的执行环境

`AIAgentReActServiceCase.chatStream` 新建 `ResponseBodyEmitter` 和 `DefaultReActFactory.DynamicContext`，注册活动请求，再把真正执行提交到业务线程池。`DynamicContext` 是**本次 HTTP 请求独有**的对象：当前步骤、工具调用/结果缓冲、assistant 正文、取消标记和 emitter 都放这里。它不是跨请求的历史数据库。

后台线程先对同一个 Agent `sessionId` 获取 `sessionLocks`，防止同一会话的两次请求并发覆盖业务历史。它还按该 `sessionId` 注册 `AgentEventPublisher` 监听器，使独立子 Runner 的过程事件进入父请求的同一条响应流；最后无论成功、失败或取消都会释放监听器和会话锁。不同业务会话可以并发执行，但如果它们最终共用同一个 SSH Shell，命令层仍会串行化写入。

### 第 2 步：RootNode 加载历史，AiCallNode 准备真正的模型输入

Case `RootNode` 从 `ConversationContextStore.initializeAndLoad(sessionId, message)` 取业务快照，写入本次 `DynamicContext`。若快照中消息历史为空，会从数据库取最近消息恢复。这里读到的是**本次请求之前**的历史，所以后面 `AiCallNode` 可以把当前用户问题单独作为 `runAsync` 的 `userContent`；不要提前把同一条用户消息重复投影进历史，否则模型会收到两份。

`AiCallNode` 的准备顺序值得逐行看：

1. 用业务 `agentId` 从装配注册表找 `Runner`，拿到本轮用户原文；重置本轮输出/工具缓冲，但不清空历史。
2. 调意图识别服务，并将意图/任务态写到上下文。复合、未知或低置信度意图仍交给主模型判断，不在这里硬编码执行子 Agent。
3. 用 `ChatContextService.trimHistory` 调 `HybridReducer` 裁剪已有历史；`tokenBudget=0` 在服务内表示使用默认预算。裁剪是为了控制下一次投影给 ADK 的历史，并不意味着把数据库历史删除。
4. `prepareAdkInvocation` 调 `CustomAdkSessionService.prepareInvocation`：把**裁剪后的历史**投影成 ADK 可读事件，并把 `terminalSessionId`、父业务会话 ID、取消对象写入 ADK Session state。当前实现只投影非空的 `user` 和 `assistant/model` 文本；工具结果不会被伪造成孤立的 `FunctionResponse`。
5. 投影完成后才把当前用户**原文**加入业务历史并落库。之后 `buildEnrichedMessage` 从当前任务、里程碑、终端状态、长期记忆、工具摘要等 Provider 拼出本轮动态提示词；发给 ADK 的当前 `Content` 使用**富化后的文本**。因此你在 UI 里输入的短句、业务历史中的原文、实际送进本轮模型的富化消息，不一定完全相同。
6. 构建 `RunConfig`：`streamingMode=SSE`，`maxLlmCalls` 约束 ADK 内部真实模型调用次数。然后 `runner.runAsync(userId, sessionId, userContent, runConfig)` 开始本轮 invocation。

别把 `Content userContent = ... Part.fromText(enrichedMessage)` 误认为发给模型的**全部**提示词。模型的最终请求还会经过 ADK 的 Agent instruction、投影历史、工具声明，以及 `LocalSpringAI` / `LocalMessageConverter` 到 Spring AI 的适配。要核对“模型到底看到了什么”，断点应继续走 `runner.runAsync` 的请求构建、`LocalSpringAI` 和模型请求日志，而不能只打印 `userContent`。同样，子 Agent 的指令和子 Runner 会形成它自己的模型请求。

### 第 3 步：ADK 内部执行工具，不由 Case 补跑

主 Agent 的模型可以先输出一句意图说明，再决定调用工具。`AiCallNode` 遍历 ADK `Event` 时把 assistant/model 的纯文本 Part 发送为 `text`，把 `FunctionCall` 记录为 `tool_call`，把 `FunctionResponse` 记录为 `tool_result`。它把连续 assistant 文本段、工具调用和工具结果按边界写回业务历史，避免“文本—工具—文本”被合成一块。工具后的模型总结也会继续产生 `text` 事件。

实际工具调用由 ADK Runner 内部驱动。整个 `runAsync` 结束后，Case 只按是否观察到工具事件路由到 `ToolCallNode`。后者按 `toolCallId` 匹配调用与结果，写里程碑、工具摘要与长期记忆；如果 ADK 有调用但缺结果，合成**错误结果**用于闭合状态，绝不再执行一次 SSH 命令。`LoopDecisionNode` 统一取消、错误、阈值或正常结束原因，**不会回跳 `AiCallNode`**。`UserFeedbackNode` 组装最终内容、发 `done`、关闭 emitter，最后回写 `ConversationContextStore`。

`RootNode` 中当前默认兜底上限为 `maxSteps=50`、`maxLlmCalls=256`、`maxToolCalls=256`、`maxToolCallsPerRound=36`。其中 Case 的 `step` 是一次完整 ADK invocation 计数；`maxLlmCalls` 才限制该 invocation 内部的真实模型调用。不要拿 `step=1` 推断“模型只调用了一次”。

## 主 Agent、子 Agent、工具到底是什么关系

把它们看成三个不同层级：

```text
配置入口 agentId=100000
└─ 主 Agent sshAgent_sshOperator（理解请求、挑选派发工具、整合结果）
   ├─ sshAgent_sshDiagnosisAgent 派发工具 ──→ 诊断子 Runner ──→ executeCommand
   ├─ sshAgent_sshChangeAgent 派发工具 ────→ 变更子 Runner ──→ executeCommand
   ├─ dispatchSubAgents ────────────────→ DAG 编排器 ──→ 多个子 Runner
   └─ planAndDispatchSubAgents ─────────→ 规划器 → DAG 编排器 → 多个子 Runner
```

“Agent 工具”并不总是直接执行 SSH。对主 Agent 来说，调用 `sshAgent_sshDiagnosisAgent` 本身就是一次 **ADK FunctionCall**；它的实现是创建一个独立子 Runner，把完整任务文本交给子 Agent。对子 Agent 来说，`executeCommand` 才是实际 SSH 工具。最后子 Agent 把执行摘要作为派发工具的 FunctionResponse 交回父 Agent，父模型可继续调用其他工具或生成总结。

### 单个子 Agent：最适合入门的断点路线

在 `ssh-agent.yml` 的主 Agent 指令中，简单诊断任务优先派给诊断子 Agent，变更任务派给变更子 Agent。模型具体选择仍取决于模型输出。以“查看当前目录”为例，若命中单个派发，调用过程是：

1. 父 ADK Runner 产生名为 `sshAgent_sshDiagnosisAgent` 的 FunctionCall；`SubAgentDispatchTool.runAsync(args, toolContext)` 收到 `request` 文本。
2. 工具从 `ToolContext.state()` 读取本次请求的 `terminalSessionId`、父业务会话 ID 和取消对象；没有终端 ID 会返回 `success=false`，这时应回头查 `AiCallNode.prepareAdkInvocation`，而不是在工具里找全局终端变量。
3. 它先发布子 Agent 开始事件，使用 `CustomRunnerFactory` 创建独立子 Runner 和子 `sessionId`，将终端 ID 等信息放入子 Session 初始 state，设置 `autoCreateSession(false)`、`streamingMode=SSE`，**先创建子 Session 再 `runner.runAsync`**。
4. `.doOnNext` 对每个子 ADK Event 调用 `AgentEventPublisher.publishToSession(parentSessionId, ...)`，因此子 Agent 的文本和工具过程可以在父请求仍运行时到达页面，不必等待全部完成。
5. 子 Runner 内的 `SshExecuteAdkTool.runAsync` 取得同一个终端 ID，调用命令安全策略和 `SshTerminalService.executeCommand`。`TerminalSessionPort.executeCommand` 在对应 Shell 中执行，并从 `AgentCommandCapture` 取得工具输出；命令文本仍会通过终端长轮询出现在左侧。
6. 子 Runner 完成后，`SubAgentResultText.finalReply` 从事件中还原子 Agent 最终回复，派发工具返回 `{success, result}` 给父 ADK Runner，并发布子 Agent 完成事件。父模型拿到该工具响应后，才可能生成用户最终看到的主 Agent 总结。

这条单子 Agent 路径**不经过** `DynamicAgentOrchestrator` / `SubAgentDispatchService`。如果给 `SubAgentDispatchService.execute` 下断点却从未进入，先在浏览器流里看父 Agent 究竟调用的是哪个工具；这不一定是故障。

### 批量与规划：任务可能并行，Shell 仍有锁

`dispatchSubAgents` 接收模型给出的 `tasks`（如 `agentName/request/dependsOn`），校验任务、白名单与 DAG，再让 `DynamicAgentOrchestrator` 按依赖调度。`planAndDispatchSubAgents` 先通过规划器生成计划，再交同一个编排器。编排器选出依赖已完成的任务，按 `maxConcurrency` 并发提交；失败时按 `failFast`、依赖状态将后续任务标为失败或跳过。真正创建每个子 Runner、超时和重试逻辑在 `SubAgentDispatchService`。这里默认子任务超时为 120 秒，`maxRetries` 决定是否重试；不要把重试误认作父 Agent 额外派发。

与单个派发相比，这条批量路径还会在 `SubAgentDispatchService` 为子 Runner 建立独立的 `childSessionId`，并使用带 `subAgent-user-` 前缀的子 Runner `userId`。二者和父业务会话 ID 都不同；但初始 state 中仍携带父业务 `sessionId` 与 `terminalSessionId`，用于事件回流和执行同一个 SSH Shell。编排器可并发安排多个子任务，`TerminalSessionPort` 的 `agentCommandLock` 仍会让同一 Shell 上的 Agent 命令依次运行。计划并行 ≠ 同一 SSH Shell 可安全交错输入命令。

### 工具安全和结果的两个通道

`SshExecuteAdkTool` 的声明工具名是 `executeCommand`，模型参数只有 `command`；终端 ID 是从运行期 state 读取的，不该让模型自己传。`DefaultCommandSafetyPolicy` 对命令做内置危险规则校验，并允许配置 `ai.ssh.command-safety.additional-deny-rules` 追加业务规则。安全拦截失败同样应形成失败工具结果，不应绕过策略直接写 Shell。

一个命令运行后会出现**两种可见数据**：左侧终端看到 Shell 原始输出，是 Reader → `/terminal/read`；右侧 Agent 看到工具结构化结果以及子 Agent 对结果的文字分析，是 `executeCommand` 的 FunctionResponse → ADK Event → `chat_stream`。它们可以同时出现，但不能相互替代。终端显示了 `docker ps` 的文本，并不自动证明模型已拿到工具结果；必须检查工具的 `success/output` 以及对应 `tool_result` 事件。

## 流式事件协议：页面为什么能按 ID 拼出执行过程

Case 层向一个 `chat_stream` 响应持续写事件。下表只列前端正在处理的主要类型；事件是否出现、出现次数由模型与派发路径决定，不应在页面上假定严格固定顺序。

| `event` | 产生位置 | 前端如何使用 |
| --- | --- | --- |
| `text` | `AiCallNode` 从父 ADK Event 提取主 Agent 文本 | 追加到当前 assistant 的正文片段 |
| `agent_start` | 子派发开始事件，经 `NestedAgentEventForwarder` 转发 | 以 `agentCallId` 创建子 Agent 执行块，展示名称和任务 |
| `agent_text` | 子 Runner 的模型文本增量 | 追加到对应 `agentCallId` 的子 Agent 区域；不重复追加为主 Agent 正文 |
| `tool_call` | ADK FunctionCall 或派发工具主动发布 | 以 `toolCallId` 创建运行中的工具项，展示工具名和命令/参数 |
| `tool_result` | 对应 FunctionResponse | 按相同 `toolCallId` 更新工具项的 `success/error` 与可查看的输出 |
| `agent_result` | 子派发结束 | 按 `agentCallId` 把子 Agent 标为完成或失败 |
| `done` | `UserFeedbackNode` | 结束本轮 UI 的流式状态；`content` 当前是序列化的最终 `ReActResultDTO` 字符串，前端会解析其正文 |
| `error` | 初始化或运行异常 | 显示异常并结束当前流的正常处理 |

`AiCallNode` 还发送 `round_end`，当前前端 `AgentStreamEvent` 解析逻辑没有把它作为可展示项。`AbstractAIAgentReActSupport` 的主工具调用事件使用历史字段名 `commend` 保存参数；`agent.ts:extractCommand` 兼容了 `command/commend/arguments/toolArgs`。新增协议字段时应同步检查前后端，而不是只改后端 DTO。

`agentCallId` 是“一次子 Agent 派发”的分组键；`toolCallId` 是“一次工具调用与结果”的配对键。一个子 Agent 可能执行多条命令，因此**一个 `agentCallId` 下可有多个 `toolCallId`**。多个子 Agent 并发时结果可能穿插返回，必须按 ID 对齐，不能仅按数组下标或时间顺序配对。`NestedAgentEventForwarder` 还会带 `parentToolCallId`、`sourceAgent` 等辅助归属信息。

典型的 UI 形态是“主 Agent 的开场文本 → 子 Agent 执行块（其中有工具项、子文本）→ 主 Agent 总结文本”。如果看到子文本和同样内容的工具结果重复展示，应查看前端 `App.tsx` 对 `agent_text`、`agent_result`、`tool_result` 的不同处理，不要把三者直接全部追加到顶层正文。

## 会话历史、动态上下文、ADK Session 各自保存什么

这个项目刻意没有把“用户长期历史由 ADK 自动累积”当作唯一方案。理解下面的生命周期，才能解释为什么每次 HTTP 都有新 `DynamicContext`，用户却仍能继续对话：

| 对象 | 生命周期和键 | 保存内容与作用 | 不能拿它当什么 |
| --- | --- | --- | --- |
| `DynamicContext` | 一次 `chat_stream` 请求，新建一次 | 本轮 ID、裁剪后历史、意图、步骤、工具调用与结果、文本、emitter、取消标记 | 不是跨请求共享对象 |
| `ConversationContextStore` | 内存 Caffeine，以业务 `sessionId` 为键 | 原始/当前任务、业务消息历史、里程碑、最近命令、工具结果；`RootNode` 加载、`UserFeedbackNode` 回写 | 不是持久数据库；进程重启会丢内存态 |
| `chat_session` / `chat_message` | 数据库记录 | 页面会话列表与消息历史；缓存缺失时 `RootNode` 可恢复最近历史 | 不是正在运行的 ADK Event 流 |
| `SessionCache` | 会话校验/过期缓存 | 把业务 `agentId + userId + sessionId` 与运行期会话关联 | 不是消息内容的唯一来源 |
| `CustomAdkSessionService` | ADK 运行期，按 `appName + userId + sessionId` 隔离 | 接收本轮裁剪历史的投影、保存 ADK 本轮工具协议事件及运行态 state | 不是业务长期历史事实来源 |
| 子 Agent ADK Session | 每次子派发独立创建 | 当前子任务文本、终端 ID、父会话 ID、取消/关联信息和子 Runner Event | 不等于父业务 `sessionId` |

`ConversationContextStore` 目前设置 `maximumSize=10_000`，**没有单独的 `expireAfterWrite/expireAfterAccess`**；正常清理依赖会话生命周期调用 `clearSession`。它保存的是业务态缓存，所以排查“历史突然没了”时要区分是 Caffeine 淘汰、进程重启、显式清理，还是数据库写入/恢复失败。`RootNode` 只有在缓存快照历史为空时才会从数据库拉取最近 50 条作为降级恢复。

需要更进一步看“本轮实际送给模型的历史”时，检查以下三个相邻位置，而不是只看 `messageHistory` 的某一次打印：

1. `RootNode` 刚加载的**请求前历史**；若缓存为空是否从 DB 恢复。
2. `AiCallNode` 的 `trimmedHistory` 与 `CustomAdkSessionService.prepareInvocation`；哪些 `user/assistant` 文本被投影、哪些工具消息仅以摘要进入动态 Prompt。
3. `AiCallNode.buildEnrichedMessage`、`Content userContent`、`LocalSpringAI` 构建的模型请求。当前用户原文在业务历史中；本轮富化文本作为 ADK 当前输入；Agent instruction 与工具 schema 由 ADK 另外组成模型请求。

`ChatContextService.buildPromptContext` 会按顺序调用启用的 Provider，聚合终端状态、当前任务、里程碑、工具摘要与长期记忆等信息，再由动态 Prompt 组件用于本轮增强。`ToolCallNode` 在工具执行**之后**归档工具摘要和里程碑，因此它们主要影响后续 Prompt；不要期待当前已经发出的模型请求反向包含这条工具结果。模型在同一次 ADK invocation 内要消费工具结果，走的是 ADK 的 FunctionResponse 协议。

## 停止按钮：为何必须先请求后端

“停止显示”和“停止执行”是两件事。页面 `App.tsx:stopChat` 先向 `/agent/stop_chat` 发送 `agentId/userId/sessionId`，等待确认或最多约 5 秒，再 abort 当前浏览器流；等待期间会忽略后续流事件。前端 abort 的作用是断开本地读取，不能单独证明 SSH 命令或子 Agent 已停。

后端 `AgentController.stopChat` → `AIAgentReActServiceCase.stopChat` 按三个 ID 匹配 `activeStreams`。命中后调用 `ReActStreamCancellation`，标记 `DynamicContext.cancelled`、取消后台 `Future`，并由 `AgentRunCancellation` 让主/子 Runner 的订阅、任务线程看到取消。批量编排器停止调度新任务；正在等待的 SSH 命令被中断时，`TerminalSessionPort.executeCommand` 不仅结束本地等待，还尝试向 Shell 发送 `Ctrl+C`。因此此处的正确断点链是：

```text
前端 App.stopChat
  → AgentController.stopChat
  → AIAgentReActServiceCase.stopChat / cancelTask
  → ReActStreamCancellation / AgentRunCancellation
  → 子 Runner 订阅或任务线程中断
  → TerminalSessionPort.executeCommand 中断分支 → Ctrl+C
```

如果 `/stop_chat` 返回 `false`，首先核对是否是同一个 `agentId/userId/sessionId`，以及活动请求是否已经自然完成。如果返回 `true` 但远端命令还在跑，要继续看取消是否穿透到当前子线程、SSH 命令是否处于可中断的等待、`Ctrl+C` 是否成功发送。`UserFeedbackNode` 正常完成时会先标记 `completed` 再 `emitter.complete()`，避免把正常关闭误判成断连取消；取消或连接断开时则可能没有正常的 `done` 事件。

## 六个可重复的断点实验

这里给出“做什么 → 在哪停 → 看到什么才算通过”，适合刚接触代码时一天内把主干走通。不要用会破坏服务器状态的命令做调试。

### 实验 A：页面上为什么没有 Agent 列表

浏览器刷新页面，Network 看 `query_ai_agent_config_list` 的**完整请求 URL 和响应**。后端从 `AgentController.queryAiAgentConfigList` 进入 `ChatService.queryAgentConfigList`，它读已绑定的 YAML 配置表。若响应为 `data: []`，先看当前 profile 导入的是 `only-agent.yml` 还是 `ssh-agent.yml`；若 404，先按上面的 profile 表核对 context path；若启动失败，回到 `AiAgentAutoConfig.onApplicationEvent` 查配置装配异常。SSH 连接列表与 Agent 列表是两个不同接口，不要相互代替。

### 实验 B：终端命令执行了，但页面没内容

打开一台测试主机，在 Shell 输入 `pwd`。先在 `RemoteTerminal.write` 看输入和终端 ID，再在 `SshTerminalController.writeToTerminal` 看后端是否收到。随后在 `TerminalSessionPortSupport.runOutputReader/appendOutput` 看是否读到字符；在 `TerminalSessionPort.readAsync` 看 `outputBuffer`、`pendingRead`；最后在前端 `RemoteTerminal.poll/emit` 和 `RemoteTerminalView` 看 `DATA.output` 是否写入 xterm。出现 `REPLACED` 说明有重叠 read；出现 `DISCONNECTED` 说明 SSH 通道已断，而不是 Agent 错误。

### 实验 C：Agent 到底看到了什么

在 `App.sendMessage` 抄下五个请求字段；后端依次停在 `AgentController.chatStream`、Case `RootNode`、`AiCallNode` 的意图识别/`trimHistory`/`prepareAdkInvocation`/`buildEnrichedMessage`，最后看 `LocalSpringAI`。逐个比较原始 `message`、请求前业务历史、`trimmedHistory`、ADK 投影事件和本轮 `userContent`；再看 Agent instruction 与工具列表。若回答似乎“忘了过去”，先确定模型请求实际带了哪些历史，再判断是 Reducer、DB 恢复、Session 投影还是模型本身的问题。

### 实验 D：子 Agent 为什么拿不到终端 ID

在 `RootNode` 看 `terminalSessionId` 非空；在 `AiCallNode.prepareAdkInvocation` 看 `TERMINAL_SESSION_STATE_KEY` 写入；再按实际派发工具停在 `SubAgentDispatchTool.runAsync` **或** `BatchSubAgentDispatchTool` / `DynamicPlanDispatchTool`；最后在子 Runner 创建时看 `initialState`，在 `SshExecuteAdkTool.runAsync` 看 `toolContext.state()`。这几个位置的终端 ID 应相同。子 `sessionId` 改变是正常的，终端 ID 改变则不是。

### 实验 E：工具成功了，页面却没有子过程/结果

同时观察三组东西：① `SshExecuteAdkTool.executeForTerminal` 的 `success/output`；② 子 Runner 产生的 `Event` 及 `AgentEventPublisher.publishToSession` 的父业务 `sessionId`；③ `NestedAgentEventForwarder` 转换的 `agent_start/agent_text/tool_call/tool_result/agent_result`。浏览器 `chat_stream` 响应里若已有事件，继续在 `agent.ts:parseStreamEvent` 和 `App.tsx` 对应 `receive` 分支下断点。Agent `sessionId` 路由错误会导致子事件不能进入正确 emitter；`agentCallId` 或 `toolCallId` 对不上会导致 UI 无法归组。

### 实验 F：点击停止以后仍在执行

发送一个**可安全中断**的测试命令，执行中点停止。依上节取消链下断点，并观察 `/stop_chat` 返回的布尔值。特别区分“前端不再显示流事件”“后台 Future 已取消”“子 Runner 已停止”“远端 Shell 收到了 `Ctrl+C`”四个检查点；仅满足第一个不算后端停止成功。

## 日志与测试提示

- 以业务 Agent `sessionId` 串起 `ReAct链路`、`ADK invocation`、子事件发布和最终 `done`；以 `terminalSessionId` 串起 `SSH 工具`、`Terminal reader` 与长轮询。观察 `toolCallId` 是否从 FunctionCall 一直匹配到 FunctionResponse。
- `MyTestPlugin` 的模型请求日志可能出现多次：一次完整请求内，ADK 在工具前、工具后都可能调用模型；规划派发还可能有独立规划模型和多个子 Agent 模型调用。不要只靠日志条数认定重复循环。
- 可先看现有纯逻辑测试 [ToolResultReconcilerTest.java](ai-ssh-terminal-server-app/src/test/java/com/jasonlat/ai/test/domain/agent/ToolResultReconcilerTest.java)、[SubAgentResultTextTest.java](ai-ssh-terminal-server-app/src/test/java/com/jasonlat/ai/test/domain/agent/SubAgentResultTextTest.java)、[ReActStreamCancellationTest.java](ai-ssh-terminal-server-app/src/test/java/com/jasonlat/ai/test/domain/agent/ReActStreamCancellationTest.java)、[CustomAdkSessionServiceTest.java](ai-ssh-terminal-server-app/src/test/java/com/jasonlat/ai/test/domain/agent/CustomAdkSessionServiceTest.java)。`SshAgentReActTest`、`SshSessionPortManualTest` 更接近联调/手动场景，运行前检查它们需要的 SSH、数据库与模型环境，不能把失败一概归因于业务代码。
- 文档中的架构图是**可编辑源码**，不是截图。打开 [docs/architecture.drawio](docs/architecture.drawio) 后可切换六个页面；如果改了调用链，请同时更新相应图页与上面的断点路线，避免图文再次脱节。
