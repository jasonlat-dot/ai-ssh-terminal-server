# AI SSH Terminal Server

AI SSH Terminal Server 是 Agent SSH 桌面客户端的后端服务，提供 SSH 连接、远程终端、SFTP 文件管理、AI 运维助手、对话记录和聊天附件等能力。

本文优先说明如何把项目运行起来，配置说明和源码结构放在后半部分。

## 一、拉取代码

当前 SFTP 功能开发分支为 `2.14-jsch-sftp`。

```bash
git clone -b 2.14-jsch-sftp https://github.com/jasonlat-dot/ai-ssh-terminal-server.git
cd ai-ssh-terminal-server
```

已经拉取过仓库时执行：

```bash
git fetch origin
git switch 2.14-jsch-sftp
git pull origin 2.14-jsch-sftp
```

## 二、准备环境

| 依赖 | 要求 | 是否必需 |
| --- | --- | --- |
| JDK | JDK 25，以根目录 `pom.xml` 为准 | 是 |
| Maven | 建议 Maven 3.9 或更高版本 | 是 |
| MySQL | MySQL 8.x | 是 |
| 文件存储 | 聊天附件可保存到本机目录或 MinIO，与 SFTP 无关 | 否 |
| SSH 服务器 | 用于终端和 SFTP 联调 | 按功能需要 |
| AI 模型服务 | OpenAI 兼容的 Chat Completions 接口 | 使用 Agent 时需要 |

先确认本机版本：

```bash
java -version
mvn -version
```

项目当前使用 Spring Boot 3.5.16、Spring AI 1.1.5、Google ADK 1.2.0 和 `com.github.mwiede:jsch:2.28.0`。

## 三、初始化 MySQL

已有 MySQL 8 可以跳过创建步骤。下面的命令会启动一个监听 `13306` 端口的测试实例：

```bash
docker run -d --name ai-ssh-mysql \
  -e MYSQL_ROOT_PASSWORD=change_me \
  -p 13306:3306 \
  mysql:8
```

连接数据库：

```bash
mysql -h 127.0.0.1 -P 13306 -u root -p
```

在 MySQL 客户端中执行：

```sql
SOURCE /你的项目路径/docs/dev-ops/mysql/1-4-ssh_terminal.sql;
SOURCE /你的项目路径/docs/dev-ops/mysql/2-14-file-upload.sql;
```

说明：

- `1-4-ssh_terminal.sql` 创建核心数据库以及 SSH、对话相关表，适合初始化新数据库。
- `2-14-file-upload.sql` 增加聊天附件元数据表。
- SFTP 会话和任务保存在进程内存中，不需要额外创建 SFTP 表。
- 核心脚本包含示例连接数据。正式使用前应删除示例记录，并从客户端重新创建自己的连接。
- 不要在已有生产数据库上直接执行带 `DROP TABLE` 的初始化脚本。

## 四、创建本地配置

根配置 `application.yml` 默认启用 `sit`：

```yaml
spring:
  profiles:
    active: sit
```

`application-sit.yml` 用于保存本地数据库密码、模型密钥等内容，已经加入 `.gitignore`，新拉取的仓库通常没有这个文件。先从生产模板复制一份。

Windows PowerShell：

```powershell
Copy-Item `
  .\ai-ssh-terminal-server-app\src\main\resources\application-prod.yml `
  .\ai-ssh-terminal-server-app\src\main\resources\application-sit.yml
```

Linux / macOS：

```bash
cp ai-ssh-terminal-server-app/src/main/resources/application-prod.yml \
   ai-ssh-terminal-server-app/src/main/resources/application-sit.yml
```

然后修改：

```text
ai-ssh-terminal-server-app/src/main/resources/application-sit.yml
```

### 必须修改的配置

| 配置 | 用途 | 建议 |
| --- | --- | --- |
| `spring.datasource.url` | MySQL 地址和数据库名 | 改成自己的 MySQL 地址 |
| `spring.datasource.username` | MySQL 用户名 | 生产环境不要使用 root |
| `spring.datasource.password` | MySQL 密码 | 使用环境变量或本地忽略文件 |
| `app.config.ssh-default-secret-key` | 加密 SSH 密码和私钥口令 | 首次启动前生成并固定保存 |
| `ai.agent.config...ai-api.base-url` | 模型服务地址 | 填写实际 OpenAI 兼容接口 |
| `ai.agent.config...ai-api.api-key` | 模型服务密钥 | 不要提交到 Git |
| `ai.agent.config...chat-model.model` | 模型名称 | 使用模型服务实际支持的名称 |
| `ai.ssh.http-proxy.enabled` | SSH 是否经过 HTTP CONNECT 代理 | 不使用代理时设为 `false` |
| `ai.file.storage.default-id` | 新上传附件使用哪个存储实例 | 桌面一体化部署建议使用 `local-main` |
| `ai.file.storage.local.enabled` | 是否启用本机目录存储 | 不依赖外部文件服务器时设为 `true` |
| `ai.file.storage.minio.enabled` | 是否启用 MinIO 附件存储 | 使用本地存储时可设为 `false` |

### 推荐的 `application-sit.yml`

```yaml
server:
  port: 8888

app:
  config:
    api-version: v1
    cross-origin: "*"
    ssh-default-secret-key: ${SSH_DEFAULT_SECRET_KEY}

spring:
  config:
    import:
      - classpath:agent/ssh-agent.yml
  datasource:
    url: jdbc:mysql://127.0.0.1:13306/ssh_terminal?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false
    username: root
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

baidu:
  api-key: ${BAIDU_API_KEY:}

ai:
  agent:
    config:
      tables:
        sshAgent:
          module:
            ai-api:
              base-url: ${AI_MODEL_BASE_URL:http://127.0.0.1:8777/}
              api-key: ${AI_MODEL_API_KEY}
              proxy:
                enabled: ${AI_MODEL_PROXY_ENABLED:false}
                host: ${AI_MODEL_PROXY_HOST:127.0.0.1}
                port: ${AI_MODEL_PROXY_PORT:7890}
            chat-model:
              model: ${AI_MODEL_NAME:gpt-5.5}

  file:
    storage:
      # 默认使用本机磁盘，适合后端随客户端安装的部署方式。
      default-id: ${FILE_STORAGE_DEFAULT_ID:local-main}
      local:
        enabled: ${LOCAL_FILE_STORAGE_ENABLED:true}
        storage-id: local-main
        root-directory: ${LOCAL_FILE_STORAGE_ROOT:${user.home}/.ai-ssh-terminal/files}
        # 默认留空，按当前上传请求动态生成协议、主机、端口和部署前缀。
        public-base-url: ${LOCAL_FILE_STORAGE_PUBLIC_BASE_URL:}
        signing-secret: ${LOCAL_FILE_STORAGE_SIGNING_SECRET:}
      minio:
        enabled: ${MINIO_ENABLED:false}

  ssh:
    sftp:
      max-total-sessions: 50
      max-sessions-per-user: 5
      max-concurrent-transfers: 20
      max-concurrent-transfers-per-user: 4
      max-concurrent-transfers-per-session: 2
      max-tasks-per-session: 20
      max-files-per-task: 10000
      max-file-size: 10GB
      max-task-size: 100GB
      session-idle-timeout: 15m
      transfer-idle-timeout: 2m
      operation-timeout: 30s
      anonymous-user-id: ${SFTP_ANONYMOUS_USER_ID:default}
    http-proxy:
      enabled: ${SSH_HTTP_PROXY_ENABLED:false}
      host: ${SSH_HTTP_PROXY_HOST:127.0.0.1}
      port: ${SSH_HTTP_PROXY_PORT:7890}
      username: ${SSH_HTTP_PROXY_USERNAME:}
      password: ${SSH_HTTP_PROXY_PASSWORD:}
```

> SFTP 配置类使用的前缀是 `ai.ssh.sftp`。YAML 中不要写成 `ai.ssh.ssh.sftp`，否则配置不会绑定，程序会使用代码里的默认值。

### 设置环境变量

Windows PowerShell：

```powershell
$env:DB_PASSWORD = "change_me"
$env:SSH_DEFAULT_SECRET_KEY = "请替换为固定的随机字符串"
$env:AI_MODEL_BASE_URL = "http://127.0.0.1:8777/"
$env:AI_MODEL_API_KEY = "your-model-api-key"
$env:AI_MODEL_NAME = "gpt-5.5"
$env:MINIO_ENABLED = "false"
$env:SSH_HTTP_PROXY_ENABLED = "false"
```

Linux / macOS：

```bash
export DB_PASSWORD='change_me'
export SSH_DEFAULT_SECRET_KEY='请替换为固定的随机字符串'
export AI_MODEL_BASE_URL='http://127.0.0.1:8777/'
export AI_MODEL_API_KEY='your-model-api-key'
export AI_MODEL_NAME='gpt-5.5'
export MINIO_ENABLED='false'
export SSH_HTTP_PROXY_ENABLED='false'
```

随机密钥可以使用以下命令生成：

```bash
openssl rand -base64 32
```

`SSH_DEFAULT_SECRET_KEY` 用于加密数据库中的 SSH 密码和私钥口令。部署后不要随意更换，否则以前加密的数据将无法解密。

## 五、启动后端

### IDE 启动

使用 IntelliJ IDEA 打开仓库根目录，运行：

```text
ai-ssh-terminal-server-app/src/main/java/com/jasonlat/ai/Application.java
```

确认运行 Profile 为 `sit`。

### 打包启动

```bash
mvn clean package -DskipTests
java -jar ai-ssh-terminal-server-app/target/ai-ssh-terminal-server-app.jar \
  --spring.profiles.active=sit
```

Windows PowerShell：

```powershell
java -jar .\ai-ssh-terminal-server-app\target\ai-ssh-terminal-server-app.jar --spring.profiles.active=sit
```

默认监听地址：

```text
http://127.0.0.1:8888
```

看到 Tomcat 启动完成日志后，再启动前端客户端。

## 六、连接前端

推荐目录结构：

```text
AIShellTerminal/
├─ ai-ssh-terminal-server/
└─ ai-ssh-terminal-client/
```

前端后端地址配置为：

```text
http://127.0.0.1:8888
```

本地联调推荐使用 `sit` Profile。`application-dev.yml` 配置了 `/api/v1` Servlet Context Path，而部分 Controller 自身已经包含 `/api/v1`，使用 `dev` 时可能出现重复路径。

## 七、配置说明

### AI 模型

`agent/ssh-agent.yml` 定义主 Agent、诊断子 Agent、变更子 Agent、模型和工具。建议在忽略提交的 `application-sit.yml` 中覆盖以下配置：

- `ai.agent.config.tables.sshAgent.module.ai-api.base-url`
- `ai.agent.config.tables.sshAgent.module.ai-api.api-key`
- `ai.agent.config.tables.sshAgent.module.chat-model.model`

模型服务必须兼容 OpenAI Chat Completions，并支持项目使用的工具调用格式。不使用百度 MCP 搜索时，可以移除对应 MCP 工具配置。

### SSH 加密密钥

`app.config.ssh-default-secret-key` 不是 SSH 登录密码，只用于服务端加密保存敏感字段。它应满足：

- 每个环境使用独立随机值。
- 第一次部署后固定保存。
- 通过环境变量或密钥管理服务注入。
- 不写入日志、不提交到 Git。

### SSH HTTP 代理

`ai.ssh.http-proxy` 是后端连接 SSH 服务器时使用的 HTTP CONNECT 代理，不是浏览器代理，也不是 AI 模型代理。

```yaml
ai:
  ssh:
    http-proxy:
      enabled: false
```

不需要代理时必须关闭。启用后，代理服务器需要允许 CONNECT 到目标 SSH 端口。

### SFTP

SFTP 与终端使用独立 SSH 连接。

| 配置 | 说明 |
| --- | --- |
| `max-total-sessions` | 整个后端允许同时存在的 SFTP 会话数 |
| `max-sessions-per-user` | 单个用户允许打开的 SFTP 会话数 |
| `max-concurrent-transfers` | 全局并发传输上限 |
| `max-concurrent-transfers-per-session` | 单个 SFTP 会话的并发传输数 |
| `max-files-per-task` | 单个目录任务允许扫描的最大文件数 |
| `max-file-size` | 单个文件大小上限 |
| `max-task-size` | 单个任务总大小上限 |
| `session-idle-timeout` | 文件管理会话空闲回收时间 |
| `transfer-idle-timeout` | 传输长时间无进度后的超时 |
| `operation-timeout` | 目录、新建、删除等操作超时 |
| `anonymous-user-id` | 未接入登录系统时使用的用户标识 |

未接入统一认证时，`anonymous-user-id` 必须与数据库 `ssh_connection.user_id` 一致，否则连接列表存在但 SFTP 无权使用该连接。

SFTP 会话保存在内存中。后端重启后，客户端原来的文件管理会话会失效，需要重新连接。

### 严格主机密钥校验

严格主机密钥校验属于每一条 SSH 连接的配置，不在全局 `application.yml` 中设置。对应数据库字段为：

- `ssh_connection_config.strict_host_key_check`
- `ssh_connection_config.known_hosts`

启用后，需要把 SSH 服务器主机公钥写入 `known_hosts`：

```bash
ssh-keyscan -t ed25519 192.168.3.16
```

保存输出的完整一行，主机/IP、算法和公钥三部分都不能省略。项目使用 `com.github.mwiede:jsch`，支持现代 OpenSSH 常用算法；最终能否连接还取决于服务器算法、JDK 安全策略和连接配置。

### 本机存储、MinIO 和聊天附件

聊天附件存储不参与 SFTP 文件传输。后端随桌面客户端一起安装时，可以直接保存到本机：

```yaml
ai:
  file:
    storage:
      default-id: local-main
      local:
        enabled: true
        storage-id: local-main
        root-directory: ${LOCAL_FILE_STORAGE_ROOT:${user.home}/.ai-ssh-terminal/files}
        # 默认留空；只有反向代理不能正确传递外部地址时才需要固定配置。
        public-base-url: ${LOCAL_FILE_STORAGE_PUBLIC_BASE_URL:}
        signing-secret: ${LOCAL_FILE_STORAGE_SIGNING_SECRET:}
      minio:
        enabled: false
```

- `root-directory` 是后端机器上的真实存储目录。默认使用用户目录，不建议写入安装目录。
- `public-base-url` 默认留空，下载地址会根据上传请求动态使用当前协议、主机、端口和部署前缀，直接修改后端端口不需要同步修改这里。
- 经过 Nginx 等反向代理时，应正确传递 `Forwarded` 或 `X-Forwarded-*` 请求头并配置 Spring 转发头策略；无法做到时再用 `public-base-url` 固定外部下载地址。
- `signing-secret` 为空时会在每次启动时随机生成，旧的临时下载链接会在重启后失效；需要保留链接时配置固定的随机密钥。
- 文件先写入同目录临时文件，写完后再移动到正式位置；接口不会把绝对磁盘路径返回前端。
- 磁盘文件名使用“后端 UUID + 已校验的小写后缀”，便于本机查看类型，同时不会暴露原始文件名。
- 数据库中的 `storage_id` 会记录实际使用的实例，因此切换默认存储不会影响已经上传的旧附件。迁移旧文件时不能只修改 `default-id`。

如果仍然使用 MinIO，需要配置 `endpoint`、`access-key`、`secret-key` 和 `bucket`，并提前创建私有 Bucket。应用不会自动创建 Bucket 或修改 Bucket 策略。`public-endpoint` 应填写浏览器能够访问的对象 API 地址，不能填写管理控制台地址。

完全不使用聊天附件时，可以同时关闭两个存储实例：

```yaml
ai:
  file:
    storage:
      local:
        enabled: false
      minio:
        enabled: false
```

## 八、生产部署

```bash
java -jar ai-ssh-terminal-server-app.jar --spring.profiles.active=prod
```

生产环境至少需要重新检查：

- 数据库地址、账号和密码
- `SSH_DEFAULT_SECRET_KEY`
- 模型地址、模型名称和 API Key
- CORS 允许来源
- SSH 和模型代理开关
- SFTP 会话、文件大小和并发限制
- 附件默认存储实例，以及本地目录或 MinIO 参数
- `SFTP_ANONYMOUS_USER_ID`，或接入真实 Spring Security Principal

生产环境不要使用 `cross-origin: "*"`，不要使用数据库 root 用户，也不要保留示例密钥和示例 SSH 连接。

## 九、常见问题

### 找不到 `application-sit.yml`

新拉取的仓库不会包含该文件。按“创建本地配置”一节从 `application-prod.yml` 复制。

### 编译提示 Java 版本不支持

确认 `java -version` 和 `mvn -version` 显示的都是 JDK 25。Maven 可能仍在使用另一套 `JAVA_HOME`。

### 数据库连接失败

检查 MySQL 地址、端口、数据库名、用户名和密码，并确认已经执行初始化 SQL。Docker 映射端口是宿主机端口，不是容器内的 `3306`。

### Agent 列表为空或对话失败

检查是否导入 `classpath:agent/ssh-agent.yml`，并核对模型 `base-url`、`api-key`、`model` 和代理设置。

### 前端请求路径出现两个 `/api/v1`

通常是启用了 `dev` Profile，同时 Controller 自身也包含 `/api/v1`。本地联调推荐使用 `sit`。

### 严格主机密钥校验失败

重新执行 `ssh-keyscan`，确认扫描的是实际连接主机和端口，并完整保存 `known_hosts` 行。主机重装或 Host Key 轮换后也需要重新核对指纹。

### 后端重启后 SFTP 无法继续上传

重启后旧 `sftpSessionId` 已失效。客户端会提示重新连接文件管理，建立新会话后再上传。

### F12 看不到 SFTP 请求

桌面客户端的 SFTP HTTP 请求由 Tauri/Rust 发出，不经过浏览器 `fetch`，因此不会显示在 WebView Network 面板中。请结合客户端提示、Tauri 日志和后端日志排查。

## 十、项目结构

| 模块 | 职责 |
| --- | --- |
| `ai-ssh-terminal-server-app` | Spring Boot 启动入口和环境配置 |
| `ai-ssh-terminal-server-trigger` | HTTP Controller、SSE 和异常处理 |
| `ai-ssh-terminal-server-case` | 应用用例编排 |
| `ai-ssh-terminal-server-domain` | Agent、SSH、SFTP、文件等领域逻辑 |
| `ai-ssh-terminal-server-infrastructure` | MySQL、SSH 会话和外部服务适配 |
| `ai-ssh-terminal-server-api` | 请求与响应 DTO |
| `ai-ssh-terminal-server-types` | 通用类型、错误码和基础对象 |
| `docs/dev-ops/mysql` | 数据库初始化及增量脚本 |
| `docs/dev-ops/environment` | Docker 和部署参考文件 |

系统架构图位于 [docs/architecture.drawio](docs/architecture.drawio)，可以使用 draw.io / diagrams.net 打开。

## 十一、安全提醒

- 不要提交数据库密码、模型 API Key、MinIO 密钥、SSH 私钥或代理密码。
- 仓库历史中出现过的真实密钥应立即在对应平台撤销并重新生成。
- `known_hosts` 应由可信渠道获取并核对指纹，不要为了省事长期关闭严格校验。
- 正式环境应接入真实用户认证；SFTP 接口只信任服务端 Principal，不接受客户端自报 `userId`。
- 修改 `SSH_DEFAULT_SECRET_KEY` 前，需要先制定已有 SSH 凭据的迁移方案。
