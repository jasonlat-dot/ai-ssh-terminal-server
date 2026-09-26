# chat_stream 图片与文件输入

沿用当前的上传接口与目录结构。前端先上传文件，再在聊天请求中传 fileId；
后端查询 file_asset，校验归属和状态，通过对应存储实例读取原对象版本，再构造模型消息。
不接收前端传入的 URL、MIME 或 Base64，也不要求模型访问内网 MinIO。

## 前端请求

路径仍是 `POST /agent/chat_stream`，部署了 servlet context-path 时按原规则添加前缀。
Content-Type 仍为 application/json，响应仍为原有逐行 JSON 事件流。

```json
{
  "agentId": "使用现有智能体ID",
  "userId": "使用现有用户ID",
  "sessionId": "使用现有会话ID，新会话可不传",
  "message": "分析截图里的错误，并结合日志说明原因",
  "attachments": [
    {"fileId": "a7779ff9-3443-48ed-8b17-d32e767d63df"},
    {"fileId": "d2a1882f-cc30-4d56-8cf3-2571f79bfc37"}
  ]
}
```

- fileId 使用 `POST /api/v1/files` 成功响应的 `data.fileId`，不是 downloadUrl 或 objectKey。
- attachments 可省略或为空，保持原有纯文本调用方式。
- 有附件时 message 可省略或为空，后端补充默认分析请求；文字和附件同时为空则拒绝。
- terminalSessionId 继续可选，单纯分析图片或文件不要求 SSH 连接。
- 重复 fileId、尚未上传完成、上传失败或无权访问的附件均拒绝，不能只忽略部分附件继续回答。
- `/agent/chat` 复用同一套附件处理逻辑。

文件正文只参与当前调用，业务历史中保存文字和附件名称、fileId 摘要，不保存 Base64 或签名 URL。
后续仅讨论已有回答可以直接发文字；需要重新检查原图或原文件时，再次携带对应 fileId。
当前未新增附件历史字段、自动重载历史附件或向独立子 Agent 传递原始附件的功能。
父 Agent 可分析本次附件，再把必要的分析结果随子任务描述传递。

## 支持的格式

| 文件 | 处理方式 | 模型要求 |
| --- | --- | --- |
| png、jpg、jpeg、webp | 校验文件头，构造 ADK inlineData → Spring AI Media | 声明支持相应 image MIME，实际模型和网关支持图片 |
| pdf | 校验 PDF 头，传原始字节，Spring AI 构造带文件名的 PDF input file | 声明 application/pdf，实际模型和网关支持 PDF |
| txt、log、csv、json、yaml、yml、md | 严格 UTF-8 解码，读取正文作为文字 Part | 普通文本模型即可 |
| docx、xlsx 等其他格式 | 返回 CHAT_ATTACHMENT_UNSUPPORTED | 后续接入独立转换策略 |

上传允许的格式不等于聊天已具备解析能力。文本不进行静默截断，超限要求前端减少内容。
文件头校验和 SHA-256 核对不等于完整文件解码或病毒扫描；损坏、加密 PDF 等仍可能由上游拒绝。

## 模型能力配置

在实际使用的 Agent YAML 中设置模块或具体 Agent 的 chat-model：

```yaml
chat-model:
  model: 你的模型名称
  supported-media-types:
    - image/png
    - image/jpeg
    - image/webp
    - application/pdf
```

默认列表为空，仅接受文字和转换后的文本附件。ssh-agent.yml 已声明上述四种媒体类型。
请按实际供应商/中转服务能力删减，配置并不能让一个纯文本模型获得图片能力。
主 Agent 如果有自己的 chat-model，采用其配置，不会继续读取模块默认的媒体能力。
这是一份显式能力声明，不根据模型名称猜测，也不额外发请求探测模型。

兼容依据：[Spring AI 1.1.5 OpenAiChatModel](https://github.com/spring-projects/spring-ai/blob/v1.1.5/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)
将 application/pdf 映射为 InputFile，其他普通媒体默认走图片分支，因此文本文件必须先读取为文字。
PDF 使用内联字节，不把预签名 URL 放入 file_data 字段。

## 限制与归属

公共 application.yml 中新增：

```yaml
ai:
  chat:
    attachments:
      max-files: 4
      max-total-size: 20MB
      max-text-chars: 60000
      max-concurrent-requests: 2
      allow-anonymous-files: true
```

这些限制独立于上传限制。并发数按单个后端实例计算，从读取附件到模型执行结束均占用许可；
纯文本请求不占附件许可。原始字节还会产生 SDK 和 Base64 开销，20 MB 不是堆内存使用上限。
流式完成、异常或任务取消退出时释放许可；ADK live Session 保留当前媒体，跨请求快照去掉媒体字节。

文件归属沿用上传时的 Principal。Controller 在进入异步线程前保存可信身份，
请求 JSON 的 userId 或 authenticatedUserId 不能替代文件所有者校验。
为兼容项目当前匿名上传，默认允许 ownerId 为空的文件；这类文件持有 fileId 即可引用，
不提供用户间隔离。接入认证后可关闭 allow-anonymous-files，已有匿名文件需重新以登录身份上传。

## 错误事件

附件加载发生在流式任务中，HTTP 200 不表示任务成功；前端必须处理 error 事件并结束等待。
业务错误保持原事件结构，新增可选的 code：

```json
{"event":"error","code":"CHAT_MODEL_MEDIA_UNSUPPORTED","content":"当前智能体未配置支持该媒体类型，请检查 chat-model.supported-media-types"}
```

| code | 含义 |
| --- | --- |
| CHAT_CONTENT_REQUIRED | 文字和附件同时为空 |
| CHAT_ATTACHMENT_INVALID | 非法/重复 ID、记录不存在或不是 UPLOADED |
| CHAT_ATTACHMENT_FORBIDDEN | 文件归属不匹配或禁止匿名文件 |
| CHAT_ATTACHMENT_LIMIT | 数量、总大小或文本正文总长度超限 |
| CHAT_ATTACHMENT_UNSUPPORTED | 未实现该格式的转换策略 |
| CHAT_ATTACHMENT_CONTENT_INVALID | 文件头、UTF-8、长度或 SHA-256 校验失败 |
| CHAT_MODEL_MEDIA_UNSUPPORTED | 主 Agent 未声明相应媒体能力 |
| CHAT_ATTACHMENT_BUSY | 附件对话并发已满 |
| FILE_READ_FAILED / FILE_STORAGE_UNAVAILABLE | 读取流或对象存储异常 |

上游实际拒绝模型请求时仍通过原有 ADK 错误事件返回。前端不要把读取流结束当成成功，
也不要对附件错误自动重复上传，因为对象可能早已保存。

## 代码位置

- api：ChatRequest、ChatAttachmentRequest、ReActEventDTO 定义协议。
- trigger：AgentController 校验基础入参并传递 Principal。
- case/react/multimodal：ChatRequestContentSupport 统一文本/附件入参规则。
- case/react：AIAgentReActServiceCase 管理附件许可和流式错误；AiCallNode 组合文字与附件 Parts。
- domain/agent/service/multimodal：ChatAttachmentService 编排文件校验与策略选择；图片、PDF、文本各自实现 ChatAttachmentConverter。
- domain/file/service：IFileService、FileService 查询并校验文件；IObjectStorageService、MinioIObjectStorageService 按原位置和版本读取。
- infrastructure：FileAssetRepository、IFileAssetDao 与 mapper 查询已有 file_asset 元数据，无数据库结构变更。
- app：ChatAttachmentProperties 和 ChatAttachmentConfiguration 绑定并装配附件限制。
- app 的 MessageConverter 按每条消息转换媒体；LocalMessageConverter 不再重复追加跨轮附件。

新增或扩展了 ChatRequestContentTest、ChatAttachmentServiceTest、FileServiceTest、MessageConverterPatchTest、
MultimodalSessionSnapshotTest，覆盖文件归属、容量、格式策略、跨轮媒体归属和快照内存保留边界。
按本次工作约定未运行编译、单元测试或真实模型/MinIO 联调。
