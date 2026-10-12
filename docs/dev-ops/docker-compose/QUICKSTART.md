# WSL Docker Compose 部署：照着执行的快速教程

这份教程说明**运行什么命令、命令会做什么、配置在哪里修改**。先采用 SQLite 方案；MySQL、自定义证书和备份的完整说明见 [详细教程](README.md)。

> 当前文件与脚本已完成配置校验，但镜像构建曾被 Docker Hub 网络超时阻断，尚未完成后端实际启动验收。请以你执行命令后得到的结果为准。

## 当前方案：自动自签证书 + SQLite + 本地附件

直接使用下文的 `compose.sh sqlite`。这个模式已经开启 HTTPS：首次启动自动生成本机自签证书、随机证书库密码和 SQLite 数据库，附件保存在 Docker 数据卷中。不会启动 MySQL 或 MinIO。

**不要选 `sqlite-tls`。** `-tls` 模式用于加载你自己准备的 `certs/server.p12` 和 `certs/ca.crt`，不负责自动生成本机证书。

`.env` 中这三项保持下面这样即可：

```dotenv
TLS_SERVER_NAME=
SERVER_SSL_KEY_STORE_PASSWORD=
SERVER_SSL_KEY_ALIAS=server
```

| 参数 | 自定义证书模式下的含义 | 自动自签模式 |
| --- | --- | --- |
| `TLS_SERVER_NAME` | 证书覆盖的主机名，用于容器健康检查的 HTTPS 主机名验证，不含协议/端口；不会自动配置 DNS | 留空，不使用 |
| `SERVER_SSL_KEY_STORE_PASSWORD` | 打开 `server.p12` 证书库的密码，不是数据库或 SSH 密码 | 留空，由后端自动管理 |
| `SERVER_SSL_KEY_ALIAS` | `server.p12` 内私钥和证书条目的别名，必须与实际条目一致 | 保留 `server`，此变量不参与自动生成 |

如果使用 Windows Tauri 客户端，另将 `.env` 的来源设置为：

```dotenv
APP_CROSS_ORIGIN=http://tauri.localhost,tauri://localhost
```

这允许桌面 WebView 调用后端。开发模式若从 `http://127.0.0.1:端口` 或 `http://localhost:端口` 加载页面，还需要将实际网页来源追加到该列表，见第 6 节。

按第 1～5 节完成配置、启动和检查；第 5 节末尾说明如何导出公开证书并连接 Windows 客户端。已有 `.env` 就直接编辑，不重新生成密钥。

## 1. 进入 WSL 和部署目录

在 Windows PowerShell 中运行：

```powershell
wsl -d debian
```

**效果：**进入本机 Debian WSL。后面的命令在 WSL 终端执行。

```bash
cd /mnt/e/project/AIShellTerminal/ai-ssh-terminal-server/docs/dev-ops/docker-compose
```

**效果：**进入部署目录。Windows 的 E 盘在 WSL 中对应 `/mnt/e`。

这套构建需要完整后端仓库，不能只复制 `docker-compose` 文件夹后启动。如果源码放在其他位置，修改上面的 `cd` 路径。

先确认 Docker：

```bash
docker compose version
sudo docker info
```

**效果：**检查 Compose 和 Docker daemon 是否可用，不启动后端。本机普通 WSL 用户没有 Docker socket 权限，因此后文使用 sudo；如果你已有权限，可省略 sudo。

## 2. 首次生成 .env 和密钥

```bash
bash init-env.sh
```

**效果：**

- 创建当前目录的 `.env`。
- 自动生成 SSH 凭据加密密钥、附件签名密钥、MySQL 初始密码。
- 不构建镜像、不启动容器，不打印密钥。

**已有 .env 时跳过这一步，直接编辑。** 脚本会拒绝覆盖旧文件，避免破坏固定密钥。

## 3. 在哪里填写你的配置

真实配置保存在：

```text
docs/dev-ops/docker-compose/.env
```

打开编辑：

```bash
nano .env
```

没有 nano 时，可在 Windows IDE 中打开同一个文件。

找到下面三项并填实际值：

```dotenv
AI_MODEL_BASE_URL=你的模型API基础地址
AI_MODEL_API_KEY=你的真实模型密钥
AI_MODEL_NAME=模型服务实际支持的模型名称
```

上面是填写格式，不能保留中文提示作为实际配置。

- 默认请求路径为 `v1/chat/completions`，基础地址与它拼接后应得到服务商要求的完整地址。
- 基础地址如果已带 `/v1`，一起核对 `AI_MODEL_COMPLETIONS_PATH`，避免重复。
- 模型服务在容器外时，容器内的 `127.0.0.1` 不能代表 Windows/WSL 主机。地址选择见 [详细教程第 5 节](README.md#5-生成本地环境文件)。
- 含空格、`#` 或 `$` 的 dotenv 值需要按 Compose 的 dotenv 规则正确加引号；敏感文本不要直接写进 Compose YAML。

nano 保存方式：`Ctrl+O` → 回车 → `Ctrl+X`。

**效果：**仅保存本地配置。编辑文件不会自动启动或重启容器。

## 4. 检查配置，再启动

先执行：

```bash
sudo bash compose.sh sqlite config --quiet
```

**效果：**检查 Compose 格式和必填参数，不启动服务。成功时一般没有输出；报错时先修正配置。`--quiet` 避免把展开后的真实参数打印出来。

确认通过后：

```bash
sudo bash compose.sh sqlite up -d --build
```

**效果：**

1. 下载构建和运行基础镜像。
2. 在容器中使用 JDK 25 和 Maven 编译后端。
3. 构建运行镜像。
4. 创建后端容器、命名卷，并在后台启动。
5. 首次启动准备 SQLite、附件目录和本机 HTTPS 证书。

数据卷会保留数据库、附件、证书和日志，正常重建容器不删除这些数据。

如果提示 `auth.docker.io ... TLS handshake timeout`，镜像还没下载成功；先处理 Docker 网络问题。排查步骤见 [详细教程](README.md)。

本机已有 `http://127.0.0.1:7890` 代理和 `laya-proxy` 构建器时，可改为先显式构建，再启动：

```bash
sudo env \
  HTTP_PROXY=http://127.0.0.1:7890 \
  HTTPS_PROXY=http://127.0.0.1:7890 \
  NO_PROXY=localhost,127.0.0.1 \
  bash compose.sh sqlite build --builder laya-proxy \
    --build-arg HTTP_PROXY --build-arg HTTPS_PROXY \
    --build-arg MAVEN_PROXY_ENABLED=true \
    --build-arg MAVEN_PROXY_HOST=127.0.0.1 \
    --build-arg MAVEN_PROXY_PORT=7890 backend

# 上一条构建成功后再执行
sudo bash compose.sh sqlite up -d --no-build
```

**效果：**为本次客户端认证请求补充代理，指定已有的代理构建器，并让构建中的 apt 和 Maven 使用同一可达代理；构建完成后使用本地镜像启动，避免启动命令再次触发未带代理的构建。这不会更改后端的模型/SSH 代理设置，也不需要重启 Docker。其他机器需要换成其实际可达代理和构建器，详见完整教程的 Docker Hub 排查小节。

## 5. 查看有没有正常启动

```bash
sudo bash compose.sh sqlite ps
```

**效果：**查看容器运行状态。等待 `healthy`；刚启动时的 `health: starting` 表示检查尚未完成。

```bash
sudo bash compose.sh sqlite logs --tail 100 backend
```

**效果：**查看最后 100 行后端日志，不修改服务。

容器内 HTTPS 检查：

```bash
sudo bash compose.sh sqlite exec backend curl \
  --fail --silent --show-error \
  --cacert /data/tls/local-backend.crt \
  https://localhost:8888/api/health
```

**效果：**使用生成的公开证书验证 HTTPS，并检查 Web 服务是否能响应。正常返回包含 `"status":"UP"` 的 JSON。

健康接口不检查模型或 SSH。接下来需要实际验收连接列表、SSH 终端、Agent 对话、上传与 SFTP。

默认本机后端地址：

```text
https://localhost:8889
```

**默认是 HTTPS，不是 HTTP。** 普通浏览器和 Windows 客户端未必自动信任容器生成的自签证书。证书信任与客户端连接步骤见 [详细教程第 8 节](README.md#8-https-验收和客户端连接)。

### 导出公开证书并连接 Windows 客户端

容器启动成功后，在同一个 WSL 部署目录执行：

```bash
mkdir -p certs
sudo bash compose.sh sqlite cp backend:/data/tls/local-backend.crt certs/local-backend.crt
sudo chmod 644 certs/local-backend.crt
curl --fail --cacert certs/local-backend.crt https://localhost:8889/api/health
```

**效果：**复制公开证书供客户端信任，允许本机用户读取这张公开证书，再从 WSL 宿主机校验 HTTPS。正常响应包含 `"status":"UP"`。`certs/` 已被 Git 忽略；不要复制私钥库或证书库密码到客户端。

公开证书对应的 Windows 路径是：

```text
E:\project\AIShellTerminal\ai-ssh-terminal-server\docs\dev-ops\docker-compose\certs\local-backend.crt
```

在支持此功能的 Windows 客户端中操作：

1. 打开后端设置 →「客户端连接」→「新增远程连接」。这里的远程连接也可用于 WSL 中独立运行的后端。
2. 名称填 `WSL SQLite`，根地址填 `https://localhost:8889`，不加 `/api/health` 等接口路径。
3. 证书验证方式选择「指定服务器自签证书（Windows 桌面）」。
4. 导入上面的 `local-backend.crt`，然后点击「测试连接」。
5. 测试通过后保存，并点击「使用此连接」。单纯保存不会切换当前连接。

当前客户端源码支持独立连接导入单张 PEM 公开证书，说明见 [客户端连接文档](../../../../ai-ssh-terminal-client/docs/backend-connections.md#连接远程-java-或-nginx)。如果已安装版本没有对应选项，需要使用支持该功能的客户端版本。

默认证书只覆盖 `localhost`、`127.0.0.1`、`::1`，本方案用于这台电脑访问；不能直接用 WSL IP、局域网 IP 或网站域名替代。公开证书更新后，应重新导出并导入客户端。普通浏览器仍使用浏览器/系统的证书信任，不会因为客户端导入了证书而自动信任。

## 6. 配置修改位置与生效命令

| 你要改什么 | 在哪里改 | 修改后怎么生效 |
| --- | --- | --- |
| 模型 API 地址 | `.env → AI_MODEL_BASE_URL` | `sudo bash compose.sh sqlite up -d` |
| 模型密钥 | `.env → AI_MODEL_API_KEY` | 同上 |
| 模型名称 | `.env → AI_MODEL_NAME` | 同上 |
| 模型请求路径 | `.env → AI_MODEL_COMPLETIONS_PATH` 等 | 同上 |
| 访问端口 | `.env → APP_PORT`，默认 `8889` | 同上 |
| JVM 参数 | `.env → JAVA_TOOL_OPTIONS` | 同上 |
| CORS 允许来源 | `.env → APP_CROSS_ORIGIN` | 同上；本次 Java 代码升级需先 `up -d --build` |
| 固定附件下载地址 | `.env → LOCAL_FILE_STORAGE_PUBLIC_BASE_URL` | 同上 |
| 上传大小、SFTP 配额、线程数 | [config/application.yml](config/application.yml) | `sudo bash compose.sh sqlite up -d --build` |
| 代理、可选决策服务开关 | [compose.yaml](compose.yaml) 的 `environment` 或另加覆盖文件 | 对应模式 `up -d` |
| Java 源码、构建工具、基础镜像 | 源码或 [Dockerfile](Dockerfile) | 对应模式 `up -d --build` |
| 自定义 HTTPS 证书 | `certs/` 和 `.env` 的 TLS 配置 | 选择 TLS 模式；详见完整教程 |

### CORS 怎么设置，已有部署怎么更新

`APP_CROSS_ORIGIN` 控制的是**网页请求的 Origin 来源**。后端按这个参数放行来源，不再将可配置值限制为 `127.0.0.1`。多个来源用逗号分隔，下面按实际客户端选择一行配置。

**允许 Tauri 桌面 WebView：**

```dotenv
APP_CROSS_ORIGIN=http://tauri.localhost,tauri://localhost
```

**仅允许本地 127.0.0.1 网页（模板默认值）：**

```dotenv
APP_CROSS_ORIGIN=http://127.0.0.1:[*],https://127.0.0.1:[*]
```

`:[*]` 表示兼容任意端口，也允许不写端口的来源。如果只想允许网页运行在某两个端口，可以缩小为：

```dotenv
APP_CROSS_ORIGIN=http://127.0.0.1:4321,https://127.0.0.1:4322
```

**同时允许 Tauri 和本地开发网页：**

```dotenv
APP_CROSS_ORIGIN=http://tauri.localhost,tauri://localhost,http://127.0.0.1:[*],https://127.0.0.1:[*]
```

也可以添加实际网页来源，例如 `http://localhost:4321` 或 `https://shellpilot.jasonlat.cc`。配置 `*` 会允许所有网页来源；需要白名单时逐项列出。直接在 `.env` 写普通文本，不要加 Markdown 链接或包住整个列表的方括号。

这里填的是**网页地址的来源**，不是后端地址。比如网页在 `http://127.0.0.1:4321`，调用 `https://localhost:8889` 后端，Origin 是前者；后端 HTTPS 证书仍需正常信任。

已有 `.env` 时直接修改这行；如果已经是你需要的 Tauri 来源，可以保留。不要重新生成 `.env` 或更换已有密钥。首次更新到这版可配置 CORS 代码，保存 `.env` 后执行：

```bash
sudo bash compose.sh sqlite config --quiet
sudo bash compose.sh sqlite up -d --build
```

**效果：**检查部署参数，重新编译包含可配置 CORS 校验的后端，并替换容器。MySQL 部署将 `sqlite` 换成 `mysql`；TLS 部署使用原来的 `sqlite-tls` / `mysql-tls` 模式。之后仅修改允许来源，执行对应模式 `up -d` 即可。

未列入配置的 Origin 会收到 HTTP 403，预检请求同样受限制。不携带 Origin 的 Rust/原生客户端请求不受 CORS 限制。

CORS 不限制连接来源 IP，也不是认证机制。Docker 宿主机端口默认仍绑定 `127.0.0.1`；容器内部监听 `0.0.0.0` 用于端口映射，两者与 CORS 是不同配置。

**两种更新命令的区别：**

```bash
# .env / Compose 参数改变：使用新参数重建必要的容器，不重新编译
sudo bash compose.sh sqlite up -d

# Java 源码 / Dockerfile / 镜像内应用配置改变：重新构建镜像
sudo bash compose.sh sqlite up -d --build
```

不要用单纯 `restart` 代替参数更新：它不会把刚修改的 Compose 环境变量重新注入旧容器。

`.env` 也不会自动把所有新变量传入容器，必须在 Compose 的 `environment` 中明确引用或传入。默认被写死为 false 的代理/决策开关，需要修改 Compose 或覆盖文件。

更新可能重启 Java，正在进行的 SSH、SFTP、Agent/SSE 会话会受影响，客户端需要重新连接。

## 7. 想用 MySQL，命令怎么改

先选定数据库模式。MySQL 方案运行：

```bash
sudo bash compose.sh mysql config --quiet
sudo bash compose.sh mysql up -d --build
sudo bash compose.sh mysql ps
sudo bash compose.sh mysql logs --tail 100 mysql backend
```

**效果：**额外创建本项目独立的 MySQL 服务，初始化新库，然后启动连接该库的后端。不会替换其他既有 MySQL 容器。

后续命令中的模式也使用 `mysql`，如：

```bash
sudo bash compose.sh mysql up -d
```

初始化密码在 `.env` 中，表结构在 [mysql/001-schema.sql](mysql/001-schema.sql)。

- SQLite 与 MySQL 是独立数据，不会自动迁移。
- 如果已经部署 SQLite，先备份并停止当前后端，再切换模式。
- 初始化 SQL 只在空 MySQL 数据卷首次初始化时执行。
- 已有 MySQL 账号改密码，需要在数据库中正确修改，再同步 `.env`；只改文件不会自动更新数据库账号密码。

## 8. 停止、再次启动，以及不能随便改的东西

```bash
# 停止服务，保留容器和数据
sudo bash compose.sh sqlite stop

# 启动已停止的容器，参数仍是创建时的参数
sudo bash compose.sh sqlite start

# 移除容器和网络，保留命名卷数据
sudo bash compose.sh sqlite down
```

MySQL 部署替换模式名即可。

日常停止不要给 `down` 加 `-v`，那会删除数据库、附件、证书所在的命名卷。

保留 `.env` 中这些固定值：

- `SSH_DEFAULT_SECRET_KEY`：更换后旧 SSH 凭据可能无法解密。
- `LOCAL_FILE_STORAGE_SIGNING_SECRET`：更换后旧附件下载链接可能失效。
- MySQL 密码：已初始化数据库要与后端配置一致。

## 9. 哪些文件提交 Git，哪些只留本机

可提交：Compose、Dockerfile、脚本、教程、无真实密钥的配置模板、初始化 SQL、`.env.example`。

继续忽略：

```text
.env
certs/
backups/
*.log
```

真实密钥填写到本地 `.env`，不要填写到准备提交的 `.env.example` 或应用 YAML 中。

备份、回退、自定义域名证书和完整故障排查请继续阅读 [详细教程](README.md)。
