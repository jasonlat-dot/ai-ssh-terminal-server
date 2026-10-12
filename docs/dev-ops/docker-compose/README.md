# ShellPilot 后端：在 WSL 中用 Docker Compose 部署

这套文件位于后端仓库的 `docs/dev-ops/docker-compose`，用于在 WSL 2 中部署 Java 后端。它与 Cloudflare Pages 上的静态官网是两个独立服务，更新后端不会更新官网。

默认方案是 **一个后端容器 + SQLite + 本地附件存储**。另外提供 MySQL 和自定义 HTTPS 证书覆盖文件。当前代码不需要为此额外部署 Redis、RabbitMQ 或 MinIO。

> 本次整理日期：2026-10-12。
>
> 已实际验证：WSL Debian 的 Docker Compose v2.39.1 能解析四种组合；Shell 脚本语法检查通过。
>
> 镜像构建在拉取 Maven 基础镜像时被 Docker Hub TLS 握手超时阻断，未完成 Java 编译或容器启动验证。文末记录了验证范围和对应排查方法。此文不是“已经部署成功”的记录。

## 快速开始：运行哪些命令，分别有什么效果

本节先按 **SQLite** 模式启动。下面命令在 WSL 终端执行，目录固定为后端仓库中的部署目录。

### 第一步：进入目录并生成密钥

```bash
cd /mnt/e/project/AIShellTerminal/ai-ssh-terminal-server/docs/dev-ops/docker-compose
bash init-env.sh
```

效果：创建仅在本机保存的 `.env`，生成固定随机密钥；不构建镜像、不启动服务。已有 `.env` 时不要重新生成，直接编辑它。

### 第二步：填写你的模型配置

```bash
nano .env
```

把 `AI_MODEL_BASE_URL`、`AI_MODEL_API_KEY`、`AI_MODEL_NAME` 改成实际值。保存并退出后再执行后续命令。没有 nano 时，使用现有编辑器。

效果：把这个部署环境的真实连接参数保存到本地。编辑文件本身不会自动重启或更新容器。

### 第三步：检查配置，不启动

```bash
sudo bash compose.sh sqlite config --quiet
```

效果：校验 Compose 和必填变量。成功时一般不输出内容；缺参数或格式错误会显示错误。采用 `--quiet` 可以避免把展开后的真实密钥打印到终端。

### 第四步：构建并在后台启动

```bash
sudo bash compose.sh sqlite up -d --build
```

效果：下载基础镜像、编译 Java 后端、构建镜像，再创建容器和数据卷并后台启动。首次启动自动准备 SQLite、附件目录和本机 HTTPS 证书。

### 第五步：查看状态和日志

```bash
sudo bash compose.sh sqlite ps
sudo bash compose.sh sqlite logs --tail 100 backend
```

效果：第一条看容器状态；第二条看最后 100 行后端日志，不修改服务。`healthy` 表示 HTTPS 健康检查通过，不保证模型、SSH 和所有业务功能都已验证。

默认连接地址为 `https://localhost:8889`。本机自签证书的客户端信任步骤见第 8 节。

如果当前 WSL 用户已有 Docker 权限，以上命令可省略 sudo。

### 换成 MySQL 时怎么运行

首次启动前选定数据库。使用 MySQL 时，上面命令中的 `sqlite` 换成 `mysql`：

```bash
sudo bash compose.sh mysql config --quiet
sudo bash compose.sh mysql up -d --build
sudo bash compose.sh mysql ps
```

效果：额外创建本项目独立的 MySQL 服务和新库，再让后端连接它。不会自动导入旧服务器/MySQL 或 SQLite 数据。已有模式切换前先停止后端并备份。

## 配置在哪里改，改完要执行什么

| 想改什么 | 修改位置 | 修改后执行 |
| --- | --- | --- |
| 模型服务地址 | `.env` 的 `AI_MODEL_BASE_URL` | 对应模式 `up -d` |
| 模型 API Key | `.env` 的 `AI_MODEL_API_KEY` | 对应模式 `up -d` |
| 模型名称 | `.env` 的 `AI_MODEL_NAME` | 对应模式 `up -d` |
| 模型请求路径 | `.env` 的 `AI_MODEL_COMPLETIONS_PATH` 等 | 对应模式 `up -d` |
| Windows/WSL 访问端口 | `.env` 的 `APP_PORT` | 对应模式 `up -d` |
| 宿主机监听地址 | `.env` 的 `APP_BIND_ADDRESS` | 对应模式 `up -d`；对外访问另需认证、防火墙、证书 |
| JVM 参数 | `.env` 的 `JAVA_TOOL_OPTIONS` | 对应模式 `up -d` |
| CORS 允许来源 | `.env` 的 `APP_CROSS_ORIGIN` | 对应模式 `up -d` |
| 固定附件下载基础地址 | `.env` 的 `LOCAL_FILE_STORAGE_PUBLIC_BASE_URL` | 对应模式 `up -d` |
| MySQL 初始密码 | `.env` 的 `MYSQL_PASSWORD` 等 | 仅新库初始化生效；已有数据库要先正确修改账号密码 |
| SQLite 或 MySQL | 运行 `compose.sh sqlite` 或 `compose.sh mysql` | 切换前备份、停止；两套数据不会自动迁移 |
| 上传大小、SFTP 配额、线程数 | `config/application.yml` | 对应模式 `up -d --build` |
| 系统决策服务、模型代理、SSH 代理开关 | `compose.yaml` 中显式的 `environment`；必要时另加覆盖文件 | 对应模式 `up -d`，确保配套地址可达 |
| 基础镜像、构建流程、系统工具 | `Dockerfile` | 对应模式 `up -d --build` |
| 新空库的表结构 | `mysql/001-schema.sql` | 只在新数据卷初始化执行；现有库必须另做迁移 |
| 自定义证书 | `certs/` 文件与 `.env` 中 TLS 变量 | 选择 `sqlite-tls` 或 `mysql-tls`，换证书后 `restart backend` |

例如，修改 `.env` 的模型名称后，在 SQLite 模式执行：

```bash
sudo bash compose.sh sqlite up -d
```

效果：Compose 会用新环境变量重建必要的后端容器，**不需要重新编译 Java**。单纯 `restart` 不会重新读取 Compose 的新环境变量。

如果修改了镜像内的 `config/application.yml` 或 Java 源码，执行：

```bash
sudo bash compose.sh sqlite up -d --build
```

效果：重新构建包含新配置/代码的镜像并替换后端容器。

`.env` 不等于“自动把所有变量送进容器”。只有 Compose 的 `environment` 显式引用或传入的变量才会生效。比如本套默认把代理和可选决策开关明确设为 false，不能只在 `.env` 新增一个同名 true 就当作已经启用，应修改 Compose/覆盖文件中的对应项。

### 什么可以提交 Git

- 可以提交：Compose、Dockerfile、脚本、无真实密钥的应用配置模板、无示例密码的初始化 SQL、教程和 `.env.example`。
- 继续忽略：真实 `.env`、`certs/` 下的证书/私钥、`backups/` 下的数据和环境文件备份、日志。
- 忽略规则同时位于后端根 `.gitignore` 和部署目录 `.gitignore`。
- 不要把真实值写进 `.env.example` 或 `config/application.yml` 再提交；这两个文件是可分享模板。

## 1. 先了解本项目的几个特殊点

| 项目 | 本套配置 |
| --- | --- |
| Java | **JDK 25**；不能换成 Java 8、17 或 21 运行本项目 |
| 构建 | 容器内 Maven 构建整个多模块项目，不要求 WSL 安装 JDK/Maven |
| 后端协议 | **HTTPS**；当前代码拒绝关闭 SSL |
| 容器内地址 | `0.0.0.0:8888` |
| 宿主机默认地址 | `https://localhost:8889` |
| 默认对外绑定 | 宿主机 `127.0.0.1`，供本机使用 |
| 数据目录 | 容器 `/data`，映射 Docker 命名卷 |
| 健康检查 | `/api/health`，使用公开证书校验 HTTPS |
| 配置来源 | 镜像内专用 `/app/config/application.yml` |
| 默认数据库 | SQLite，首次启动自动建表 |
| 可选数据库 | 新的 MySQL 8.4 容器，独立命名卷 |
| 运行用户 | 非 root，UID/GID `10001` |

原项目 YAML 默认只监听 `127.0.0.1`，这在容器里会阻止宿主机端口转发访问，所以 Compose 显式改成容器内监听 `0.0.0.0`。宿主机发布端口仍限制在回环地址。

原项目部分本地配置带有环境相关默认值，本套构建不打包这些 `application*.yml`，而使用专用配置和 `.env`，不会把本机模型/数据库配置复制进镜像。

默认使用 8889，是为了尽量避开桌面客户端可能已经启动的本机 8888 后端。需要时可以通过 `.env` 修改宿主机端口，容器内端口保持 8888。

## 2. 文件说明

```text
docs/dev-ops/docker-compose/
├── README.md                       本教程
├── compose.yaml                    默认后端 + SQLite
├── compose.mysql.yaml              添加 MySQL 并覆盖后端数据库配置
├── compose.tls.yaml                使用自定义证书
├── Dockerfile                      JDK 25 多阶段构建
├── Dockerfile.dockerignore         只允许必要构建输入，排除本地配置
├── maven-settings.xml              使用 HTTPS Maven Central
├── config/application.yml          容器专用 Spring 配置
├── mysql/001-schema.sql            空库初始化；无删表、无示例连接
├── .env.example                    环境变量模板，无真实凭据
├── init-env.sh                     生成本地 .env 和固定随机密钥
├── compose.sh                      选择部署模式的命令包装器
├── .gitignore                      忽略 .env、证书和备份
└── .gitattributes                  保持脚本和配置的 LF 换行
```

本套文件需要和**完整后端仓库**一起使用。不能只把这个目录复制到 WSL 后直接构建，因为 Docker 构建还需要仓库根目录和各 Java 模块的源码。

## 3. 确认 WSL 和 Docker 可用

Windows PowerShell：

```powershell
wsl --list --verbose
```

本机实际检查到的发行版为 **debian**，版本 **2**。可以进入：

```powershell
wsl -d debian
```

以下命令都在 **WSL 的 Bash 终端**运行。

```bash
docker compose version
docker info
```

### 已安装 Docker，但提示 permission denied

本机普通用户访问 `/var/run/docker.sock` 没有权限，root 可以访问 Docker。

可以先用：

```bash
sudo docker info
```

如果这能成功，后面将包装脚本命令写成：

```bash
sudo bash compose.sh sqlite ps
```

如果当前用户本来就有 Docker 权限，或已经是 WSL root，则使用 `bash compose.sh ...` 即可。本教程后续按普通用户需要 sudo 的情况写。

不需要为了运行这套后端修改现有容器。本机已有 MySQL、Redis、RabbitMQ 等容器，本套默认 SQLite 不连接或修改它们；可选 MySQL 也通过 Compose 项目名隔离。

### 还没有 Docker

两种方式选择一种：

- 使用 Docker Desktop：开启 WSL 2 engine 和对应发行版的 **WSL Integration**，并使用 Linux containers。
- 在 WSL 中安装 Docker Engine 和 Compose 插件：按你的发行版参考 Docker 官方安装说明。

不要在同一个发行版里混用两套 Docker 管理方式。使用 Docker Desktop 时，Docker Desktop 必须处于运行状态。

参考：[Docker Desktop 的 WSL 2 集成](https://docs.docker.com/desktop/features/wsl/)、[Docker Engine 的 Debian 安装说明](https://docs.docker.com/engine/install/debian/)。

## 4. 进入部署目录

Windows 的 E 盘在 WSL 中通常是 `/mnt/e`，所以当前项目可以直接进入：

```bash
cd /mnt/e/project/AIShellTerminal/ai-ssh-terminal-server/docs/dev-ops/docker-compose
```

后面所有命令都在这个目录运行。

长期开发和反复构建时，可以把完整源码放进 WSL 自己的 Linux 文件系统，例如 `~/projects`，以减少跨文件系统开销。这里的命名卷数据由 Docker 保存，不放在 E 盘源码目录中。

部署模板、脚本和教程纳入 Git 版本管理，可随仓库拉取。真实 `.env`、`certs/`、`backups/` 和日志仍被忽略；`.env.example` 只保存无凭据的字段模板。

## 5. 生成本地环境文件

首次运行：

```bash
bash init-env.sh
```

脚本会：

1. 从 `.env.example` 创建 `.env`。
2. 生成 SSH 凭据加密密钥。
3. 生成附件下载链接签名密钥。
4. 生成可选 MySQL 的普通账号和 root 密码。
5. 限制 `.env` 的文件权限，且不会把密钥打印到终端。

如果 `.env` 已存在，脚本会退出，不会覆盖旧密钥。这是为了避免已有 SSH 凭据无法解密。

使用编辑器打开：

```bash
nano .env
```

如果 WSL 没有 nano，可以用现有 IDE 或其他文本编辑器打开这个目录的 `.env`；不必因此更换部署方式。

必须填写这三项：

```dotenv
AI_MODEL_BASE_URL=
AI_MODEL_API_KEY=
AI_MODEL_NAME=
```

填你实际使用的模型服务地址、密钥和模型名称。模型接口需要兼容本项目的 OpenAI Chat Completions 调用和工具调用格式。

### base URL 怎么填写

配置将基础地址和请求路径分别保存：

- `AI_MODEL_BASE_URL`：基础地址。
- `AI_MODEL_COMPLETIONS_PATH`：默认 `v1/chat/completions`。
- `AI_MODEL_EMBEDDINGS_PATH`：默认 `v1/embeddings`。

例如，最终请求路径应为：

```text
https://模型服务域名/v1/chat/completions
```

基础地址应相应填写 `https://模型服务域名/`。如果服务商提供带前缀或带 `/v1` 的基础地址，请一起核对路径变量，避免最后出现重复的 `/v1/v1`。

不要把浏览器控制台地址当作 API 地址。

### 模型服务在 Windows 或 WSL 上怎么办

**容器内的 127.0.0.1 指向后端容器自己。** 不能直接用它访问容器外的模型服务。

Compose 添加了：

```text
host.docker.internal → Docker host gateway
```

- Docker Desktop 场景：这个名字通常用于访问 Windows 主机服务。
- WSL 原生 Docker Engine 场景：它指向 Docker 所在的 WSL 主机，不等同于 Windows 主机。
- WSL NAT 场景访问 Windows 服务时，可以参考 Microsoft 的 WSL 网络说明查询 Windows 主机 IP，并确认服务监听的接口与 Windows 防火墙允许访问。
- 访问其他容器时，使用可达的服务地址/发布端口，或者按实际部署设计共享网络。本配置没有自动加入既有容器网络。

可以先在 WSL 中测试模型服务的实际地址，再填写 `.env`。后端默认关闭模型 HTTP 代理和 SSH HTTP CONNECT 代理，不会依赖 `127.0.0.1:7890`。

参考：[WSL 网络与 Windows 主机访问](https://learn.microsoft.com/en-us/windows/wsl/networking)。

### 哪些密钥不能随便改

| 变量 | 作用 | 变更影响 |
| --- | --- | --- |
| `SSH_DEFAULT_SECRET_KEY` | 加密数据库中的 SSH 密码/私钥口令 | 换掉后旧凭据可能无法解密 |
| `LOCAL_FILE_STORAGE_SIGNING_SECRET` | 签名附件临时下载链接 | 换掉后旧链接可能失效 |
| `MYSQL_PASSWORD` | 可选数据库普通账号密码 | 改 .env 不会自动修改已经初始化的数据库账号 |
| `MYSQL_ROOT_PASSWORD` | 可选数据库管理账号密码 | 同样不会自动更新已存在的数据卷 |

更新服务时保留 `.env`，不要重新生成这些密钥。已有数据库若使用另一套加密密钥，迁移时要同时保留那套密钥；不要套用本次新生成值解密旧数据。

## 6. 默认部署：SQLite

适合先在 WSL 上运行、个人使用以及不需要外部 MySQL 的情况。

先检查配置：

```bash
sudo bash compose.sh sqlite config --quiet
```

若提示 `Set the model...`，回到 `.env` 补齐模型参数。这个检查不启动容器。

开始构建并启动：

```bash
sudo bash compose.sh sqlite up -d --build
```

首次构建要下载基础镜像和 Maven 依赖，耗时取决于网络。整个 Java 多模块项目在构建容器中编译，宿主机不需要安装 JDK 25。

查看状态和日志：

```bash
sudo bash compose.sh sqlite ps
sudo bash compose.sh sqlite logs --tail 100 backend
```

看到服务为 `healthy` 后继续验收。健康检查有启动等待时间，刚启动时出现 `health: starting` 不等于失败。

容器内会使用 HTTPS，首次启动在数据卷中生成证书、数据库和附件目录。

## 7. 可选部署：MySQL

如果希望用独立 MySQL 数据库：

```bash
sudo bash compose.sh mysql config --quiet
sudo bash compose.sh mysql up -d --build
sudo bash compose.sh mysql ps
sudo bash compose.sh mysql logs --tail 100 mysql backend
```

这会添加一个名为 `mysql` 的 Compose 服务：

- 镜像为 `mysql:8.4`。
- 数据库名为 `ssh_terminal`。
- 后端使用普通账号 `shellpilot`。
- 数据库端口不发布到宿主机。
- MySQL 的命名卷与后端数据卷独立。
- 首次初始化会执行 `mysql/001-schema.sql`。
- 只有普通账号能够读取附件表后，才允许后端启动。

这个 MySQL 属于 `shellpilot-backend` Compose 项目，不会替换现有的、恰好叫 `mysql` 的其他容器。本配置没有设置固定 `container_name`。

### 初始化脚本与原脚本有什么区别

`mysql/001-schema.sql` 提取了现有两个 SQL 文件的 8 张表结构：

- `chat_message`
- `chat_milestone`
- `chat_session`
- `core_memory`
- `long_term_memory`
- `ssh_connection`
- `ssh_connection_config`
- `file_asset`

只保留 `CREATE TABLE IF NOT EXISTS`，没有 `DROP TABLE`，也没有示例 SSH 主机或密码数据。

只用于**新数据卷初始化**。MySQL 官方镜像不会在每次重启时重复执行初始化目录中的脚本。已有数据库后续加字段/迁移需要单独安排，不要通过删数据卷来“重新初始化”。

### SQLite 与 MySQL 不会自动迁移

两个模式访问两套独立的数据。切换后看到空连接列表，并不表示旧数据库被删除。

切换模式前先备份、停止正在运行的后端，再用新模式启动；不要同时运行两个后端实例共享同一个 SQLite 数据文件。

以后运行 `ps`、`logs`、`up`、`down` 等命令，要持续使用实际部署的模式。例如，采用 MySQL 后就使用 `compose.sh mysql ...`。

## 8. HTTPS 验收和客户端连接

默认本机地址：

```text
https://localhost:8889
```

健康接口：

```text
https://localhost:8889/api/health
```

### 先在容器里检查

SQLite 模式：

```bash
sudo bash compose.sh sqlite exec backend curl \
  --fail --silent --show-error \
  --cacert /data/tls/local-backend.crt \
  https://localhost:8888/api/health
```

正常结果含有：

```json
{"status":"UP","checkedAt":"实际检查时间"}
```

该健康接口只检查 Spring Web 是否可响应，不访问模型 API、SSH 服务器或数据库业务查询。健康成功后，还需要实际创建 SSH 连接、打开终端，并做一次 Agent 对话和文件功能验收。

### 从 WSL 宿主机检查

先复制**公开证书**，不是私钥或密码：

```bash
mkdir -p certs
sudo bash compose.sh sqlite cp backend:/data/tls/local-backend.crt certs/local-backend.crt
curl --fail --cacert certs/local-backend.crt https://localhost:8889/api/health
```

MySQL 模式时，把命令中的 `sqlite` 换成 `mysql`。

浏览器不自动信任这张独立生成的自签证书，所以直接打开页面可能提示证书不受信任。诊断时可以使用上面的 `--cacert`；不应把关闭全局 TLS 校验作为正常部署配置。

### 连接桌面客户端时注意

WSL/容器生成的证书位于 Docker 数据卷中，Windows 桌面客户端不一定能自动找到它。该证书也不等于客户端内置后端原来生成的证书。

因此，只有“容器健康”不能保证“桌面客户端已经信任这个容器”：

1. 使用客户端的自定义后端地址，并填实际可达的 HTTPS 地址。
2. 按客户端支持的证书信任方式配置公开证书；自动本机证书固定信任依赖客户端能够读取匹配的证书。
3. 需要稳定的远程/域名连接时，使用下一节的匹配域名且受信任证书。
4. 不要把当前只含 localhost/回环 IP 的自签证书拿去访问局域网 IP 或远程域名。

Windows 到 WSL 的 localhost 访问受具体 WSL/Docker 网络模式影响。访问失败时先区分：端口能否连通、证书是否匹配/受信任、应用是否已启动。

## 9. 可选：使用域名或局域网主机的自定义证书

默认部署只服务本机。需要其他设备访问时，还需要规划访问控制、防火墙和可信 HTTPS。本项目部分接口仍按匿名用户运行，不应只把绑定地址改成 `0.0.0.0` 就当作安全的公网服务。

准备：

```text
certs/server.p12   包含私钥和证书链的 PKCS12 文件
certs/ca.crt       健康检查信任的 CA 证书或 CA 证书链
```

证书必须覆盖客户端使用的 DNS 主机名，PKCS12 默认条目别名为 `server`；如果不同，修改别名变量。

在 `.env` 中设置：

```dotenv
TLS_SERVER_NAME=你的证书对应主机名
SERVER_SSL_KEY_STORE_PASSWORD=实际的PKCS12密码
SERVER_SSL_KEY_ALIAS=server
```

这是说明格式，填写时必须使用实际值。

保证容器 UID `10001` 能读取证书文件，同时保护其中私钥。不要把私钥库或密码复制给客户端；客户端只需要适当的公开信任材料。

启动时选择对应模式：

```bash
sudo bash compose.sh sqlite-tls up -d --build
```

或者：

```bash
sudo bash compose.sh mysql-tls up -d --build
```

TLS 覆盖文件会：

- 禁用默认证书自动生成。
- 加载 `/certs/server.p12`。
- 保留 Java HTTPS。
- 用 `ca.crt` 和正确的 TLS 主机名检查容器健康。

容器健康检查中的 `--resolve` 只用于把这个主机名定向到容器回环地址，不会自动为客户端配置 DNS。

如果证书或密码错误，后端会拒绝启动，不会退回到明文 HTTP。后续证书更新后，应重启服务使配置生效。

本教程没有自动申请域名证书或部署公网反向代理。若另加 Nginx/Caddy，Java 上游仍须走 HTTPS，并验证其证书和主机名；可参考后端现有的 [本地 HTTPS / HTTP2 文档](../../local-https-http2.md)。

## 10. 数据保存在哪里，怎么备份

Compose 项目名固定为 `shellpilot-backend`。默认命名卷：

| 数据卷 | 内容 |
| --- | --- |
| `shellpilot-backend_backend-data` | SQLite 文件、上传附件、TLS 证书、日志 |
| `shellpilot-backend_mysql-data` | 可选 MySQL 数据 |

后端卷内结构：

```text
/data/
├── data/ai-ssh-terminal.db
├── files/
├── tls/
│   ├── local-backend.p12
│   ├── keystore.password
│   └── local-backend.crt
└── logs/
```

不会因为正常容器重建而消失。

查看卷：

```bash
sudo docker volume ls --filter label=com.docker.compose.project=shellpilot-backend
```

### SQLite 备份：停写后备份整个数据目录

为了同时保留 SQLite/WAL、附件、证书和日志，先停止后端，再备份：

```bash
umask 077
mkdir -p backups
sudo bash compose.sh sqlite stop backend
sudo bash compose.sh sqlite run --rm --no-deps --entrypoint tar backend \
  -czf - -C /data . > "backups/backend-data-$(date +%Y%m%d-%H%M%S).tar.gz"
cp -p .env "backups/env-$(date +%Y%m%d-%H%M%S)"
sudo bash compose.sh sqlite start backend
```

如果中途备份失败，确认错误后手动执行 `start backend` 恢复服务。

备份包含 SSH 凭据、证书私钥和环境密钥，应像原始数据一样保管。仅备份数据库却遗失 `SSH_DEFAULT_SECRET_KEY`，可能无法解密连接凭据。

### MySQL 备份

MySQL 模式需额外备份数据库；停止后端后导出：

```bash
umask 077
mkdir -p backups
sudo bash compose.sh mysql stop backend
sudo bash compose.sh mysql exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --no-tablespaces -u "$MYSQL_USER" "$MYSQL_DATABASE"' \
  > "backups/mysql-$(date +%Y%m%d-%H%M%S).sql"
sudo bash compose.sh mysql run --rm --no-deps --entrypoint tar backend \
  -czf - -C /data . > "backups/backend-data-$(date +%Y%m%d-%H%M%S).tar.gz"
cp -p .env "backups/env-$(date +%Y%m%d-%H%M%S)"
sudo bash compose.sh mysql start backend
```

自定义证书模式还需要另行备份宿主机 `certs` 目录。恢复前应先停止后端、保存现有数据，再选择匹配的数据库与文件备份、固定密钥和证书。不要直接把旧备份覆盖到仍在运行的数据库中。

## 11. 后续更新、停止与回退

以下示例为 SQLite 模式，其他模式替换第一个参数。

### 更新代码并重新构建

同步正确的后端代码后：

```bash
sudo bash compose.sh sqlite up -d --build
sudo bash compose.sh sqlite ps
sudo bash compose.sh sqlite logs --tail 100 backend
```

保留 `.env` 和命名卷，更新后检查健康接口与实际业务。

**后端更新与静态官网更新不同。** 重建后端容器会重启 Java 进程，内存中的 SSH 终端、SFTP 会话和正在进行的 SSE/Agent 请求可能中断；客户端需要重新连接。这套单实例 Compose 不提供零停机更新。

### 普通停止与再次启动

```bash
sudo bash compose.sh sqlite stop
sudo bash compose.sh sqlite start
```

### 移除容器和网络，但保留数据

```bash
sudo bash compose.sh sqlite down
```

再次启动：

```bash
sudo bash compose.sh sqlite up -d
```

不要在日常停止时添加 `-v`；它会删除 Compose 命名卷，导致数据库、附件、TLS 私钥等数据丢失。

### 回退镜像

建议每次正式发布前在 `.env` 中将 `BACKEND_IMAGE_TAG` 设成独立版本，例如 `release-20261012-1`，构建并记录对应 Git 提交。

需要回退时：

1. 先确认旧镜像还在：`sudo docker image ls shellpilot-backend`。
2. 备份数据。
3. 将 `.env` 的 `BACKEND_IMAGE_TAG` 改为已存在的旧版本。
4. 使用 `sudo bash compose.sh sqlite up -d --no-build --pull never` 切换。
5. 核对健康与业务功能。

回退会重启后端，且不会自动回退数据库结构。若新版本做过数据库迁移，要先确认旧版本与当前数据库兼容。只改 Git 代码不会自动替换这个 WSL 后端，仍要手动重新构建部署。

## 12. 常见问题

### Docker Hub TLS handshake timeout

本次构建验证实际遇到：

```text
failed to fetch anonymous token
auth.docker.io
TLS handshake timeout
```

这是镜像下载阶段的网络错误，还没有进入 Java 编译，不是模型 API Key 的错误。

可以单独验证：

```bash
sudo docker pull maven:3.10.0-eclipse-temurin-25-noble
sudo docker pull eclipse-temurin:25-jdk-noble
```

两张基础镜像能拉取后，再构建。检查 Docker 的网络、代理和镜像访问策略；只给当前 Bash 设置代理，不一定会让 Docker daemon 或 BuildKit 使用该代理。不要为下载镜像关闭 TLS 证书验证。

Dockerfile 使用 Docker 自带前端，已避免额外下载 `docker/dockerfile` 前端镜像。本次也用内置 default 构建器尝试过，基础镜像下载仍超时；没有修改全局代理、构建器或 Docker daemon 配置。

### Maven 无法下载依赖

配置默认通过 HTTPS Maven Central 解析依赖，避开原 POM 中的旧 HTTP 仓库地址。

- 网络不可达时，检查 Docker 构建阶段的出站访问。
- 出现 `Could not find artifact` 时，确认指定版本是否已发布。
- 私有或只存在本机 Maven 缓存的依赖，需要配置合法可访问的私有仓库，或预先安装所需构件；不能把宿主机缓存存在当作构建容器里也存在。
- 本方案不复制用户 Maven settings、仓库密码或本机整个 `.m2`。

如需给构建器提供带认证的 Maven settings，应使用 BuildKit secret 挂载，而不是把凭据写进 Dockerfile/镜像。本次没有替你添加私有仓库凭据。

### application-sit.yml 不存在

容器不需要这个文件。部署使用 `config/application.yml`，显式通过 `SPRING_CONFIG_LOCATION` 指定。

原来的开发 YAML 可以继续保留在开发环境，不会通过本 Dockerfile 打进运行镜像。

### Connection refused 或端口占用

- 先看容器日志与健康状态。
- 访问宿主机用 `8889`，容器内用 `8888`。
- 使用 `https://`，不是 `http://`。
- 若宿主机端口被占用，修改 `.env` 的 `APP_PORT`，再执行 `up -d`。
- 用 `ss -ltn` 查看宿主机监听；检查 WSL 与 Windows 的网络模式和防火墙。

### curl 提示证书错误

自签证书只覆盖 localhost/回环地址。用 `--cacert` 明确信任公开证书，并使用匹配的主机名。远程域名使用自定义证书模式。

### 容器启动时找不到 keytool

运行镜像使用完整 JDK 25 并在构建中检查 keytool。不要直接改成不包含该工具的精简 Java 运行镜像，默认 TLS 自动生成依赖它。

### 更新后 SSH 密码无法解密

检查是否保留原来的 `SSH_DEFAULT_SECRET_KEY`。重建镜像不应重新生成这把密钥。

### MySQL 改密码后连不上

官方镜像的初始化密码变量只在新数据库卷初始化时生效。已经存在的账号密码需要在数据库内正确修改，并同步后端配置；不能只改 `.env`。

### 健康通过，Agent 对话失败

健康接口不测试模型。核对模型基础地址、请求路径、模型名称、API Key、容器网络和工具调用兼容性，再看后端日志。

### 本机的 Jev/Laya 怎么接入

本套默认关闭可选的结构化决策服务，先让基本后端独立运行。

需要接入时，在单独 Compose 覆盖文件中显式配置 `AI_DECISION_ENABLED`、对应各功能开关、可达的 `AI_DECISION_URL` 等环境变量。容器不能用自身的 127.0.0.1 访问 WSL 上已经存在的 Laya 服务。

## 13. 本次验证范围

已经执行：

- 阅读当前 JDK、配置加载、数据库、TLS 自动生成和健康接口代码。
- 确认 WSL Debian 为 WSL 2。
- 确认 WSL root 可访问 Docker 28.3.3，Compose 为 v2.39.1。
- 使用无效测试凭据验证 SQLite、MySQL、SQLite + 自定义 TLS、MySQL + 自定义 TLS 四种 Compose 合并配置。
- 检查 Shell 脚本语法。
- 在 WSL 临时目录实际运行环境生成脚本，确认四项随机密钥独立、文件权限为 0600、再次执行不覆盖已有密钥，并清理临时文件。
- 初始化 SQL 仅含 8 张表结构，无 DROP 或示例 INSERT。
- 检查本地环境文件、证书、备份的忽略规则。

未完成：

- 从 Docker Hub 拉取 Java/Maven 基础镜像。
- 容器内 Maven 编译与运行镜像构建。
- Java 服务启动、SQLite/MySQL 业务、TLS 健康检查和桌面客户端连接的实际运行验收。

原因是本机 Docker Hub 认证请求 TLS 握手超时。没有启动本套后端，没有修改或停止已有的其他容器，也没有创建真实模型配置。部署文件的 Git 提交不等于后端已启动。

## 14. 官方参考

- [Docker Compose：健康检查与启动依赖](https://docs.docker.com/compose/how-tos/startup-order/)
- [Docker Compose：多文件合并规则](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)
- [Docker：Dockerfile 专用忽略文件](https://docs.docker.com/build/concepts/context/#dockerignore-files)
- [Docker：MySQL 官方镜像](https://hub.docker.com/_/mysql)
- [Docker：Maven 官方镜像标签来源](https://github.com/docker-library/official-images/blob/master/library/maven)
- [Docker：Eclipse Temurin 官方镜像标签来源](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin)
- [Microsoft：WSL 网络](https://learn.microsoft.com/en-us/windows/wsl/networking)
