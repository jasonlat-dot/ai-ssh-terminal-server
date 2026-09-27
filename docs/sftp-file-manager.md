# Tauri SFTP 文件管理对接

本功能使用独立 SSH Session，不依赖终端是否打开，也不经过 MinIO。Tauri Rust 层读取本地文件、执行 HTTP 流传输并写本地文件；页面仅显示目录与进度。所有路径均为远程 POSIX 路径，目录请求按需加载。

## 实现位置

- `api/.../dto/sftp/SftpRequests`：接口入参，禁止客户端指定 userId。
- `trigger/.../http/sftp/SftpController`：JSON、原始文件流和 SSE。
- `trigger/.../http/sftp/SftpEventStream`：一个窗口一个订阅、进度节流、断线重连快照。
- `case/.../sftp/SftpServiceCase`：统一门面、Principal 身份、连接归属、命令转换。
- `domain/.../sftp/service/SftpService`：会话/任务配额、目录清单、传输状态、超时回收。
- `domain/.../sftp/model/entity/TransferTaskEntity`：条目状态与原子进度快照。
- `domain/.../sftp/adapter/port/ISftpClientPort`：SDK 无关的连接和 Channel 端口。
- `infrastructure/.../adapter/port/sftp/JschSftpClientPort`：独立 JSch 连接、HTTP CONNECT 代理、SFTP 操作与进度适配。
- `app/.../config/SftpConfiguration`、`properties/SftpProperties`：配置与装配。

## 配置和身份

部署配置位于 `application.yml` 的 `ai.ssh.sftp`，SIT 可覆盖。原有 multipart 的 20MB 限制不用于本功能：文件接口接收 `application/octet-stream`，单文件与任务容量由 SFTP 策略检查。反向代理需要允许对应大小的请求、关闭上传/下载缓冲，并配置长传输的空闲超时。

优先采用服务端 Principal。项目未接入登录时，固定使用 `anonymous-user-id`（默认 `default`）；该值必须与已有 SSH 连接记录的 userId 一致。生产将其设为空并接入认证。客户端不能使用请求 body/header 中的自报用户 ID 切换身份。Rust HTTP/SSE 请求应携带同一认证信息。

SFTP 使用保存的 SSH 用户、密码/私钥以及全局 HTTP CONNECT 代理。存在高级配置时遵守 `strictHostKeyCheck`，`knownHosts` 是 known_hosts 文件正文；启用严格检查但未提供匹配主机密钥会建连失败。没有高级配置时沿用当前项目的非严格检查行为。SFTP 只能使用 SSH 账号本身的文件权限，不通过 sudo 提权。

`knownHosts` 保存的是服务器身份密钥，不是用户登录私钥。当前 JSch 默认支持 `ssh-ed25519`、`ecdsa-sha2-nistp256`、`ecdsa-sha2-nistp384`、`ecdsa-sha2-nistp521`，以及采用 `rsa-sha2-256`/`rsa-sha2-512` 签名的 RSA 主机密钥；RSA 的 known_hosts 类型通常仍显示为 `ssh-rsa`。旧的 RSA/SHA-1 `ssh-rsa` 签名和 `ssh-dss` 默认不启用。项目使用 Java 25，可直接使用 Ed25519，无需额外密码学 Provider。

## 接口清单

统一前缀 `/api/v1/sftp`。JSON 成功格式沿用项目：`{"code":"SUCCESS_0000","info":"...","data":...}`。

| 方法与路径 | 用途 |
| --- | --- |
| POST /sessions | 打开独立文件管理会话 |
| GET /sessions/{id} | 查询会话、根目录 |
| DELETE /sessions/{id} | 关闭会话并取消传输，移除任务 |
| GET /sessions/{id}/entries?path=... | 浏览目录，省略 path 使用根目录 |
| POST /sessions/{id}/directories | 创建一个目录，父目录必须存在 |
| POST /sessions/{id}/files | 创建一个空文件；目标已存在时拒绝覆盖 |
| DELETE /sessions/{id}/entries?path=... | 删除普通文件或空目录，不支持递归删除 |
| GET /sessions/{id}/transfers | 当前窗口任务完整快照 |
| GET /sessions/{id}/events | SSE 进度与心跳 |
| POST /transfers | 创建上传/下载清单 |
| GET /transfers/{id} | 任务与每个条目的状态 |
| PUT /transfers/{id}/items/{itemId}/content | 上传文件原始字节 |
| GET /transfers/{id}/items/{itemId}/content | 下载文件原始字节 |
| POST /transfers/{id}/items/{itemId}/confirm | 下载到本地后的成功/失败确认 |
| POST /transfers/{id}/cancel | 取消任务 |

### 1. 打开窗口和浏览服务器

```json
{"connectionId":"已有连接ID","rootPath":"/opt/apps"}
```

省略 rootPath 时使用远程账号默认目录。返回 `sftpSessionId/connectionId/rootPath/createdAt`。窗口的根目录在创建时固定，后续访问不能超出该目录。需要浏览整个账号可访问的文件系统时，可显式选择 `/`。

右侧调用 `entries`，条目包含 `name/path/kind/size/modifiedAt/permissions`。`modifiedAt` 为 epoch 毫秒，`kind` 为 FILE、DIRECTORY、SYMLINK 或 OTHER。目录总大小不递归计算；超大目录超过上限明确返回错误，不静默截断。

左侧本地目录完全由 Tauri Rust 层提供，无须先将文件上传到 Java。

### 2. 上传文件或目录

创建任务示例（文件长度必须使用真实值）：

```json
{
  "sftpSessionId":"窗口会话ID",
  "direction":"UPLOAD",
  "remotePath":"/opt/apps",
  "conflict":"FAIL",
  "items":[
    {"relativePath":"demo/config/app.yml","kind":"FILE","size":128},
    {"relativePath":"demo/app.jar","kind":"FILE","size":1048576},
    {"relativePath":"demo/empty","kind":"DIRECTORY","size":0}
  ]
}
```

父目录会自动补全，空目录必须显式提交。`relativePath` 不允许绝对路径、空段或 `..`。默认 FAIL 拒绝已存在目标；SKIP 跳过已存在普通文件。目录存在则复用，文件/目录冲突则报错。没有覆盖策略，避免将不同 SFTP 服务器的覆盖语义误认为一致。

任务返回完整 items，每个条目都有后端生成的 `itemId`。对状态 PENDING 的 FILE 条目执行 PUT，Content-Type 为 `application/octet-stream`，正文直接使用 Rust 本地文件流，不使用 multipart 或 Base64。推荐每个窗口并发两个文件，收到 429 后由客户端延迟重试。

服务端会写同目录的 `.sftp-<transferId>-<itemId>.part`，校验实际长度、远端长度及冲突后 rename 提交。只有 PUT 成功且条目 COMPLETED 才算完成。目录准备可能已经产生空目录，后续失败不会递归删除这些目录。

重传规则：FAILED 文件可以重新 PUT，相同 itemId 同时执行会返回 409，已完成文件不允许重复上传。如果第一次 PUT 成功但 HTTP 响应丢失，应先查询任务，不能盲目再次上传。取消是整个任务的终态，重新传输需要创建新任务。

### 3. 下载文件或目录

```json
{
  "sftpSessionId":"窗口会话ID",
  "direction":"DOWNLOAD",
  "remotePath":"/opt/apps/demo"
}
```

下载清单由后端扫描生成，不接受客户端指定文件大小。扫描完成之前，界面显示“正在扫描”，不要显示虚构的百分比。返回的相对路径包含选择的顶层文件/目录名，例如 `demo/app.jar`。Tauri 必须再次校验路径可用于本地平台且不能越出选定下载目录，并提前检测 Windows 大小写或保留文件名冲突。

对 DIRECTORY 条目先创建本地目录，成功后发送 confirm；对 FILE 条目调用 GET content，将响应流写入本地临时文件，不把文件正文搬进 JS 内存。确认 HTTP 状态、接收长度和后端条目状态，成功关闭本地文件、提交到目标路径后发送：

```json
{"saved":true}
```

后端 `SENT` 表示发送完成，不表示本地保存成功。前端可能在服务端写入 SENT 前已收到最后一个数据块，应等待任务条目变为 SENT 后再确认；短暂 409 可查询后重试确认。本地保存失败使用 `saved:false`，条目变为 FAILED，可重新 GET。

下载流中途失败时，HTTP 可能已经是 200，后端不会向二进制正文追加 JSON。必须检查最终字节数和任务状态，不能仅凭 HTTP 200 宣布成功。后端不设置 Content-Length，预期大小取自任务清单。

零字节文件同样要执行 GET/确认。空目录也要创建并确认，否则任务保持 AWAITING_CONFIRMATION。首版检查源文件大小变化，不提供一致性快照；正在写入的同大小文件仍可能变化，下载静态文件或由业务先制作快照。

### 4. 进度和断线

订阅 `/sessions/{id}/events`，事件名为 `progress`，data 为当前窗口全部任务的进度数组。首次连接发送当前快照，后续变化默认每 300ms 合并发送，15 秒注释心跳。重连会替换同窗口旧订阅，不会重启任务。

每项进度包含：

- transferId、direction、status。
- totalBytes、transferredBytes：字节进度，SKIP 的文件不计入总字节。
- completedItems、totalItems、failedItems、awaitingConfirmation。
- activeItems：正在传输的条目及各自字节进度。
- updatedAt：快照变化时间。

完成/失败计数变化后查询任务详情，取得失败原因和最新条目列表。总大小为零时按条目状态显示，不做除零运算。SFTP 字节到达 100% 仍可能处于远程提交或本地保存确认阶段。

上传进度以远程 SFTP 写入为准；Tauri 可以另外显示向后端发送的进度。下载本地进度由 Rust 实际接收/写入统计。不要将两条链路字节相加制造大于 100% 的进度。

SSE 断线不取消文件任务；文件 HTTP 断线则该条目失败。查询与 SSE 不刷新会话空闲时间；实际浏览、创建目录、扫描或传输才刷新。活动操作期间不会因窗口空闲回收，但持续无字节进展会触发传输超时。

收到 `session-closed` 事件，或重连时返回会话 404，停止重连旧 sftpSessionId，显示“文件管理会话已结束”，由用户重新打开窗口；不要自动建连绕过空闲回收。

## 生命周期和首版边界

- 不新增数据库表。会话和任务在本节点内存，重启后失效；多实例部署需要会话粘滞路由，不能让同一个任务随机落到不同实例。
- 每个窗口独立 SSH Session；每个传输独占 Channel。关闭终端不影响 SFTP，关闭 SFTP 不影响终端。用户删除连接配置后，已经打开的 SFTP 窗口仍需显式关闭或等待空闲回收。
- 默认最多 50 个窗口、全局 20 个传输、每用户 4 个、每窗口 2 个；元数据操作每窗口最多一个。全部任务条目总量也有上限。
- 超过 completed-task-retention 无更新且无活动操作的任务会被移除，包括放弃的 PENDING/SENT/FAILED 任务，避免永久占用容量。
- 路径按远程 POSIX 规则处理。首版拒绝跟随符号链接和特殊文件；目录下载遇到它们明确失败。JSch 0.1.x 路径接口会解释通配符，首版拒绝含 `*`、`?`、反斜杠或控制字符的路径。
- 应用层 realpath 检查不替代服务器端权限隔离；若要求目录隔离，应使用受限 SSH 账号和服务端 SFTP chroot，避免远端并发修改路径带来的竞态。
- 首版不提供覆盖、断点续传、ZIP 打包、权限修改或递归删除。文件管理仅允许删除普通文件和已经为空的目录，并禁止删除会话根目录；Tauri 可以按清单直接下载整个目录，无需 ZIP。
- 取消或失败会尽力删除本次上传的临时文件；网络断开或服务器故障时删除无法保证，日志记录残留临时路径。不要自动重试可能已经成功的 rename，先查询任务并检查远端文件。
- 不使用固定的“传输 10 秒超时”；只限制持续无进展的时间。扫描和普通目录操作另有 operation-timeout。

## 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| SFTP_UNAUTHORIZED | 401 | 缺少认证，且未配置本地固定身份 |
| SFTP_NOT_FOUND | 404 | 连接/会话/任务/文件不存在，或不属于当前用户 |
| SFTP_FORBIDDEN / SFTP_PATH_FORBIDDEN | 403 | 文件权限不足或路径不允许 |
| SFTP_INVALID / SFTP_SIZE_MISMATCH | 400 | 参数错误或文件长度不符 |
| SFTP_CONFLICT / SFTP_SOURCE_CHANGED / SFTP_CANCELLED | 409 | 同名冲突、源变化、取消或超时 |
| SFTP_LIMIT | 413 | 文件/目录清单超过容量限制 |
| SFTP_BUSY | 429 | 并发或任务配额已满 |
| SFTP_UNAVAILABLE / SFTP_IO_ERROR | 502 | SSH/SFTP 或文件流错误 |

按本次要求未运行编译和测试。联调时建议覆盖：上传/下载单文件、空文件、嵌套目录与空目录；关闭终端继续传输；传输中取消；断网后重查状态；同名文件；不同用户读取任务；错误长度与路径；SSE 重连；空闲回收与并发上限。
