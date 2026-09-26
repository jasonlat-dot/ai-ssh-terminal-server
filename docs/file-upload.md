# 独立文件上传（2.14）

本次只提供后端代传的同步上传功能，不修改 chat_stream，不接入文件解析、病毒扫描或 Agent。
上传成功表示文件和元数据均已存储，状态为 UPLOADED；不表示内容已通过安全扫描。

## 启用步骤

1. 在应用实际使用的 MySQL 数据库执行 `docs/dev-ops/mysql/2-14-file-upload.sql`。
   脚本仅新增 file_asset 表，不会删除现有数据；应用不会自动执行该 SQL。
2. 在 MinIO 预先创建私有桶。上传账号需要该桶的 PutObject、GetObject、DeleteObject 权限；
   如果启用版本控制，补偿删除还需要相应版本删除权限。应用不会自动建桶或开放公共读写。
3. 在公共 `application.yml` 的 `ai.file` 下填写配置，或者设置环境变量：

```bash
export MINIO_ENABLED=true
export MINIO_ENDPOINT=http://127.0.0.1:9000
export MINIO_BUCKET=ai-attachments
export MINIO_ACCESS_KEY='<应用账号>'
export MINIO_SECRET_KEY='<应用密钥>'
# 可选：浏览器无法访问内部地址时，填写指向同一个 MinIO 服务的外部对象 API 地址。
export MINIO_PUBLIC_ENDPOINT=https://files.example.com
```

公共配置会与 application-sit.yml 等环境配置合并，后者可以覆盖同名配置。
endpoint 是对象 API 地址，不能填写控制台地址。public-endpoint 必须在签名前使用；
不能在返回给前端后直接替换 URL 的域名或路径，否则签名会失效。
region 默认 us-east-1，应与桶所在区域一致。

MinIO 默认未启用，空配置不会初始化客户端或连接存储。请求上传时才返回明确错误。
没有启用文件功能时不要求执行新增表 SQL，上传会在访问数据库前因未配置存储而失败。

## 请求与响应

Controller 路径：`POST /api/v1/files`，Content-Type 为 multipart/form-data，字段名为 `file`。
与项目现有 SSH Controller 一样，路径包含 /api/v1；若部署设置了 servlet context-path，
需要在 URL 前另加该前缀，避免误以为本接口会自动去掉重复的前缀。

```bash
curl -X POST 'http://localhost:8888/api/v1/files' \
  -F 'file=@./example.txt'
```

浏览器使用 FormData，交给浏览器自动设置带 boundary 的 Content-Type：

```javascript
const formData = new FormData();
formData.append("file", file);
const response = await fetch("/api/v1/files", {
  method: "POST",
  body: formData
});
const result = await response.json();
if (!response.ok || result.code !== "SUCCESS_0000") {
  throw new Error(result.info);
}
console.log(result.data.fileId, result.data.downloadUrl);
```

成功响应（HTTP 200）：

```json
{
  "code": "SUCCESS_0000",
  "info": "上传成功",
  "data": {
    "fileId": "服务端生成的UUID",
    "fileName": "example.txt",
    "contentType": "text/plain",
    "size": 123,
    "sha256": "服务端计算的SHA-256",
    "status": "UPLOADED",
    "downloadUrl": "临时签名下载地址",
    "urlExpiresAt": "2026-09-26T10:15:00Z"
  }
}
```

下载地址默认 15 分钟有效。数据库不保存该地址。
这一阶段不提供历史文件查询、重新签名或删除接口；对应接口将在接入权限与引用管理时扩展。
当前所有文件强制以 application/octet-stream 附件下载，不进行浏览器内联预览。
响应中的 contentType 仅为规范化后的客户端声明类型，不代表实际内容检测结果。

错误响应统一为 `{code, info, data: null}`：

| HTTP | code | 含义 |
| --- | --- | --- |
| 503 | FILE_STORAGE_NOT_CONFIGURED | 没有启用存储或默认存储 ID 未注册 |
| 503 | FILE_STORAGE_CONFIG_INVALID | 已启用，但端点、密钥、桶等配置不完整或不合法 |
| 503 | FILE_STORAGE_UNAVAILABLE | 存储网络、凭证、桶权限等异常 |
| 400 | FILE_INVALID | 缺少 file、空文件、非法文件名或无效 multipart |
| 400 | FILE_TYPE_NOT_ALLOWED | 扩展名未在允许列表中 |
| 413 | FILE_TOO_LARGE | 超过文件或 multipart 请求大小上限 |
| 429 | FILE_UPLOAD_BUSY | 当前实例正在向存储传输的上传数量超过限制 |
| 500 | FILE_UPLOAD_FAILED | 读取流、元数据写入等其他失败 |

例如没有配置 MinIO 时：

```json
{
  "code": "FILE_STORAGE_NOT_CONFIGURED",
  "info": "未配置或启用文件存储服务，请联系管理员配置 MinIO 等存储服务",
  "data": null
}
```

## 代码入口

- trigger/http/FileController：接收 multipart 和已有认证 Principal。
- cases/IFileServiceCase、cases/file/FileServiceCase：统一文件门面，选择存储实例，负责流的打开和关闭、DTO 转换。
- cases/file/storage/ObjectStorageResolver、DefaultObjectStorageResolver：应用层的选择契约与注册表实现，通过 storageId 选择存储并触发配置检查。
- domain/file/service/FileService：使用 case 传入的存储端口，负责参数校验、并发准入、随机对象路径、SHA-256、元数据及失败补偿。
- domain/file/adapter/port/ObjectStoragePort：存储厂商无关的领域端口。
- infrastructure/adapter/port/storage/minio/MinioObjectStorage：懒初始化 SDK、流式分片上传、签名下载和补偿删除。
- infrastructure/adapter/repository/FileAssetRepository：文件记录持久化。
- trigger/http/advice/FileExceptionHandler：文件接口范围的统一 HTTP 状态与错误码映射。

配置绑定集中在 app 的 `com.jasonlat.ai.config.properties` 包：FileStorageProperties、
FileUploadProperties，以及 SSH 的 SshCommandProperties、SshHttpProxyProperties、TerminalSessionProperties。
这些类由 FileServiceConfiguration / SshInfrastructureConfiguration 使用
`@EnableConfigurationProperties` 注册，不再由下层组件扫描注册。

app 将绑定结果转换成不可变参数再注入下层：
- domain 使用 FileUploadPolicy，文件大小是普通 long 字节数，不依赖 Spring DataSize。
- infrastructure 使用 MinioStorageSettings、SshCommandSettings、SshHttpProxySettings、TerminalSessionSettings。
- case 的存储选择器由 app 显式装配，只接收默认存储 ID 和存储实现列表。

所有 YAML 配置键和默认值保持不变，下层不引用 app 的 Properties 类。

调用顺序为：Controller → FileServiceCase → ObjectStorageResolver 选定端口 → FileService 执行上传。
case 和 domain 都只依赖 ObjectStoragePort，不依赖 MinIO SDK 或基础层的实现类。
领域服务不再依赖选择器，也不读取 default-id；本次上传、签名及失败补偿使用同一个传入实例，
实例只作为方法参数传递，不修改单例服务的共享状态。并发许可仍由同一个领域服务统一管理。
未配置存储时，case 在打开输入流和调用领域服务前返回 FILE_STORAGE_NOT_CONFIGURED。
基础层保留数据库持久化和对象存储 SDK 适配，负责对接外部系统；应用层负责选择使用哪个实例。

接入 OSS 时新增 ObjectStoragePort 实现及其配置，注册不同的 storageId，再更改 default-id。
无需修改 Controller、case 和上传领域流程。旧记录仍保存原来的 storageId，不能直接覆盖其含义。

## 边界与资源管理

- 当前项目没有统一登录认证实现。存在可信 Principal 时保存其名称；没有时 owner_id 为空，
  不接受前端自行提交的 userId 充当认证身份。本次不新增认证系统；
  部署若要求登录上传，应由统一认证入口保护该路径。未接入前不要将接口作为匿名公共上传入口。
- 默认每个文件 20 MB，请求总大小 21 MB；可通过 FILE_MAX_SIZE、FILE_MAX_REQUEST_SIZE 调整。
- 扩展名白名单只是上传准入，不是实际内容验证。当前无解析器、杀毒或异步处理任务。
- multipart 使用临时磁盘，应用上传流程不调用 getBytes()；MinIO 每个上传使用固定 5 MiB 分片缓冲，
  实际内存还有 SDK/HTTP 开销。FILE_MAX_CONCURRENT_UPLOADS 默认 4，仅限制每个实例到存储的传输，
  不限制容器已接收的 multipart 临时文件数量。网关和容器仍需按部署容量设置连接、速率与临时盘限制。
- 配置中的 timeout 为存储 HTTP 请求超时，上传过程不持有数据库长事务。
- 没有新增 CORS 通配授权。跨域前端应沿用网关或项目统一 CORS 配置。
- 不实现跨用户文件去重、配额、幂等键、分片续传和上传后自动清理，这些不属于本次独立上传闭环。

## 失败补偿

先插入 UPLOADING，再向对象存储写入；写入成功后保存版本号和摘要，生成下载地址并更新 UPLOADED。
数据库初始化失败不会触发对象上传。上传后的任何步骤失败会尝试删除对象并记录失败状态；
已获得版本号时按具体版本删除。

PUT 未返回确认结果（如网络超时），或补偿删除失败，记录 CLEANUP_REQUIRED。
删除返回成功也不保证超时的 PUT 不会稍后完成，所以不能将这种不确定结果直接视为已清理。
进程被终止或数据库持续不可用可能留下 UPLOADING。本阶段没有后台回收任务：
需要按 file_id、storage_id、bucket、object_key、object_version 核对残留对象，
尤其注意版本桶中的历史版本和未完成 multipart。
建议在存储侧配置未完成 multipart 的清理策略，后续再接入持久化清理任务。

## 测试

FileServiceTest、FileServiceCaseTest、FileStorageResolverTest、FileControllerTest 覆盖未配置存储、配置不完整、
上传摘要与版本、超限、数据库失败补偿、补偿失败、并发许可释放和 HTTP 错误响应。
测试使用模拟存储，不会连接真实 MinIO。FileAndSshConfigurationTest 额外验证 app 配置绑定、
参数转换，以及未配置/不完整 MinIO 配置不会阻止启动。

本次环境没有 Java/Maven，未执行编译、单元测试或真实上传。配置好 JDK 25 与 Maven 后可运行：

```bash
mvn -pl ai-ssh-terminal-server-app -am test \
  -DskipTests=false \
  -Dtest=FileServiceTest,FileServiceCaseTest,FileStorageResolverTest,FileControllerTest,FileAndSshConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
