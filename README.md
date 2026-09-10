# AI Agent 脚手架

基于 Spring Boot + Spring AI + Google ADK 构建的 AI Agent 脚手架项目。通过简单的 YAML 配置，即可快速创建、运行智能体（Agent），支持**串行**、**并行**工作流、工具调用、流式输出等能力。


| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.4.3 | 基础框架 |
| Spring AI | 1.1.0-M3 | LLM 调用封装 |
| Google ADK | 0.4.0 | Agent 开发套件 |
| Java | 17+ | 运行环境 |



```bash
https://github.com/jasonlat-dot/ai-agent-scaffold
```


在 `application.yml` 或环境变量中配置 LLM 的 API Key：

```yaml
# application.yml
glm:
  api-key: your-api-key-here
baidu:
  api-key: your-baidu-api-key-here
```


> chat-ui 为纯静态 HTML/CSS/JS，可直接双击 `index.html` 打开，无需启动后端。


只需编写一个 YAML 配置文件，放在 `src/main/resources/agent/` 目录下即可。


```yaml
ai:
  agent:
    config:
      tables:
        myAgent:
          app-name: myAgent
          agentDefinition:
            agent-id: myAgent
            agent-name: 我的助手
            agent-desc: 一个通用智能体
          module:
            ai-api:
              base-url: https://open.bigmodel.cn/api/paas/
              api-key: ${glm.api-key}
              completions-path: v4/chat/completions
              embeddings-path: v4/embeddings
            chat-model:
              model: glm-4.7-flash
            llm-agents:
              - name: MyAssistant
                description: 通用助手
                instruction: |
                  你是一个通用助手，擅长解答日常问题和编程问题。
                chat-model:
                  model: glm-4.7-flash
            runner:
              agent-name: MyAssistant
```


三步流水线：编写代码 → 代码审查 → 重构优化

```yaml
ai:
  agent:
    config:
      tables:
        codePipeline:
          app-name: codePipeline
          agentDefinition:
            agent-id: codePipeline
            agent-name: 代码流水线
            agent-desc: 自动编写、审查、重构代码
          module:
            ai-api:
              base-url: https://open.bigmodel.cn/api/paas/
              api-key: ${glm.api-key}
              completions-path: v4/chat/completions
              embeddings-path: v4/embeddings
            chat-model:
              model: glm-4.7-flash
            llm-agents:
              - name: CodeWriterAgent
                description: 编写 Java 代码
                instruction: |
                  根据用户需求编写 Java 代码，输出格式：```java ... ```
                output-key: generated_code
              - name: CodeReviewerAgent
                description: 审查代码
                instruction: |
                  审查以下代码：{generated_code}
                output-key: review_comments
              - name: CodeRefactorerAgent
                description: 重构代码
                instruction: |
                  根据审查意见重构代码：{review_comments}
                output-key: refactored_code
            agent-workflows:
              - type: sequential
                name: CodePipeline
                sub-agents:
                  - CodeWriterAgent
                  - CodeReviewerAgent
                  - CodeRefactorerAgent
            runner:
              agent-name: CodePipeline
```


多路并行研究 → 结果汇总

```yaml
ai:
  agent:
    config:
      tables:
        researchPipeline:
          app-name: researchPipeline
          agentDefinition:
            agent-id: researchPipeline
            agent-name: 并行研究管道
            agent-desc: 并行研究多个主题并汇总
          module:
            ai-api:
              base-url: https://open.bigmodel.cn/api/paas/
              api-key: ${glm.api-key}
              completions-path: v4/chat/completions
              embeddings-path: v4/embeddings
            chat-model:
              model: glm-4.7-flash
            llm-agents:
              - name: ResearcherA
                description: 研究可再生能源
                instruction: 请研究可再生能源的最新进展。
                output-key: renewable_result
              - name: ResearcherB
                description: 研究电动汽车
                instruction: 请研究电动汽车的最新发展。
                output-key: ev_result
              - name: SynthesisAgent
                description: 汇总报告
                instruction: |
                  汇总以下研究结果：
                  - {renewable_result}
                  - {ev_result}
                output-key: final_report
            agent-workflows:
              - type: parallel
                name: ParallelResearch
                sub-agents:
                  - ResearcherA
                  - ResearcherB
              - type: sequential
                name: ResearchPipeline
                sub-agents:
                  - ParallelResearch
                  - SynthesisAgent
            runner:
              agent-name: ResearchPipeline
```



定义具体的智能体，每个智能体包含：
- **name**: 智能体名称（唯一标识）
- **description**: 描述（用于流程编排）
- **instruction**: 指令（告诉智能体做什么）
- **output-key**: 输出变量名（用于在后续 Agent 间传递）
- **chat-model**: 可覆盖全局模型配置
- **tool-skills-list**: 可选，技能列表（如文件读取、搜索等）


编排多个智能体的执行顺序：

| 类型 | 说明 |
|------|------|
| `sequential` | 串行执行，按顺序一个接一个 |
| `parallel` | 并行执行，多个 Agent 同时运行 |
| `conditional` | 条件分支（按需扩展） |


指定最终入口智能体，作为整个 Agent 的执行起点。


工具配置在 `chat-model` 节点下，支持接入外部工具：

- **SSE**: 如百度搜索等远程 SSE 服务
- **Local Bean**: 本地注册的 Spring Bean 工具

```yaml
chat-model:
  model: glm-4.7-flash
  tool-mcp-list:
    - sse:
        name: baidu-search
        base-uri: http://appbuilder.baidu.com
        sse-endpoint: /v2/ai_search/mcp/sse?api_key=${baidu.api-key}
        request-timeout: 300000
    - local:
        bean-name: myToolCallbackProvider
```


| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/agent/chat` | POST | 普通对话（非流式） |
| `/api/v1/agent/chat_stream` | POST | 流式对话（SSE） |
| `/api/v1/agent/list` | GET | 获取所有 Agent 列表 |
| `/api/v1/agent/sessions` | GET | 获取用户的会话列表 |
| `/api/v1/agent/sessions/{sessionId}` | DELETE | 删除会话 |


```bash
curl -X POST http://localhost:8888/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "agentId": "testAgent",
    "sessionId": "sessionId"
    "message": "帮我写一个Hello World"
  }'
```


```
ai-ssh-terminal-server/
├── ai-ssh-terminal-server-api          # API 层
├── ai-ssh-terminal-server-app          # 应用层（配置文件、测试）
├── ai-ssh-terminal-server-domain       # 领域层（Agent 核心逻辑）
├── ai-ssh-terminal-server-infrastructure # 基础设施层
├── ai-ssh-terminal-server-trigger      # 触发层（HTTP 接口）
├── ai-ssh-terminal-server-types        # 类型定义
└── chat-ui                             # 前端界面（静态 HTML）
```



在 `chat-model` 节点下单独指定 `model`：

```yaml
chat-model:
  model: glm-4-flash   # 例如换成 glm-4-flash
```


通过 `output-key` 定义变量名，在后续 Agent 的 instruction 中用 `{变量名}` 引用：

```yaml
# Agent A
output-key: code_result

# Agent B 的 instruction
instruction: 根据以下代码分析：{code_result}
```


1. 实现 `ToolCallbackProvider` 接口
2. 注册为 Spring Bean
3. 在 YAML 的 `chat-model.tool-mcp-list` 中引用

```yaml
chat-model:
  tool-mcp-list:
    - local:
        bean-name: myToolCallbackProvider
```


Apache License 2.0
