# 默认本机 HTTPS / HTTP/2

后端强制启用 HTTPS，默认启用 `server.http2.enabled`。监听端口不变，
连接地址改为 `https://127.0.0.1:<端口>`。TLS 通过 ALPN 与支持 HTTP/2 的客户端协商 `h2`；
只设置 HTTP/2 而继续使用 `http://` 得到的是 h2c，浏览器/WebView 不会因此自动切换到 h2。

## 证书文件

YAML 显式声明默认的 `server.ssl.key-store` 地址；`LocalBackendTlsEnvironmentPostProcessor`
在 Tomcat 启动前检查此地址。开启 `server.ssl.auto-generate` 时，首次启动在
`<app.config.data-directory>/tls` 生成独立的私钥和证书，以后优先复用有效证书。
无需手工执行 OpenSSL，也不会把共享私钥或 API 密钥写入安装包。

默认配置如下；私钥库密码由生成器从本机文件读取后注入本次启动的内存配置，不回写 YAML：

```yaml
server:
  ssl:
    enabled: true
    auto-generate: ${LOCAL_TLS_AUTO_GENERATE:true}
    key-store: ${SERVER_SSL_KEY_STORE:file:${app.config.data-directory}/tls/local-backend.p12}
```

`server.ssl.enabled` 固定为 `true`，不再提供关闭 HTTPS 的开关；通过 YAML、环境变量或命令行
设置为 `false` 时拒绝启动。容器定制器同时检查实际 TLS 协议，禁止附加明文 HTTP/AJP 连接器，
包括仅用于跳转的 HTTP 端口。`server.ssl.auto-generate` 只控制是否自动管理默认证书，
关闭它不会关闭 HTTPS。`auto-generate` 是本应用自定义扩展项，由上述处理器
读取，并非 Spring Boot 内置 SSL 参数。旧版外置配置未声明这些新字段时保留原有默认行为，
证书位置、随机密码及客户端公开证书文件不变，无需删除已有 TLS 文件。

| 文件 | 用途 |
| --- | --- |
| `local-backend.p12` | PKCS12 私钥库，只保留在本机 |
| `keystore.password` | 本机随机私钥库密码，不要分享或提交 |
| `local-backend.crt` | 公开证书，供客户端核对信任 |
| `generation.lock` | 防止多进程同时生成证书 |

证书有效期一年，后端启动时发现剩余不足 30 天会续期。证书仅包含 `localhost`、
`127.0.0.1` 和 `::1`；不适用于远程域名或局域网 IP。密钥库/密码损坏时拒绝启动，
不会静默改为明文 HTTP，也不会自动覆盖不可读的私钥。

Java 运行时必须包含 `bin/keytool`。客户端的 jlink 打包脚本已检查该工具是否存在。
TLS 目录和默认私钥文件限制为当前运行用户访问；Windows 使用 ACL，POSIX 使用用户权限。
数据根目录本身仍应为当前用户私有目录，不应对所有用户开放。

## Windows 桌面客户端

主窗口安装 WebView2 证书事件后，启动后台线程从上述目录读取公开证书。
仅当请求为当前本机后端的 HTTPS 回环地址、端口一致、证书内容完全一致且在有效期内时，
才接受这张自签证书。WebView2 在会话中缓存主机与证书的允许决定；
客户端在切换证书、协议或端口时清除旧决定。不设置 `--ignore-certificate-errors`，
不向系统根证书库添加信任项。
原生文件传输客户端也仅在该本机范围内加载此证书；远程服务仍走标准 TLS 校验。

启动成功后，客户端同步本机后端的 HTTPS 地址和端口，兼容旧版默认 HTTP 地址。
自定义远程地址或其他本地端口不会被覆盖。默认本地端口 8888/8889 视为旧版本本机地址。
旧版本 HTTP 地址升级到 HTTPS 后，按后端地址划分的会话缓存可能显示为新的地址空间。

需要同时重启新后端与新客户端；安装版需重新打包以更新 JAR 和桌面壳。
开发模式手工启动后端时，可直接连接 IDEA 中的服务，不需要开启内置后端。
每个 HTTPS 回环请求在发送前准备本机证书：开发壳优先读取后端源码 YAML 中的数据目录，
并兼容用户默认数据目录与外置配置目录。证书文件必须由后端先生成；
客户端已运行时，启动 IDEA 后点击健康检查即可重新加载，无需启动第二个 Java 进程。
SSH/Agent/流式聊天/健康检查与原生文件传输共用该准备机制，不会改动 YAML 或迁移数据。
不使用 Tauri 的普通浏览器，以及非 Windows WebView，不使用上述 Windows 固定信任机制；
需自行信任公开证书或使用已受信任的正式证书。不要给浏览器添加全局忽略证书错误参数。

## 验证协议

客户端开发者工具的 Network 面板中显示 `Protocol` 列，查看真实后端请求是否为 `h2`。
看到 HTTPS 或后端启动日志并不能单独证明请求已经使用 HTTP/2。
终端长轮询和 SSE 在 h2 下共享连接中的不同流，不再分别占满 HTTP/1.1 的少量连接槽。
前端不再用全局 6 个名额限制终端读取；各终端独立长轮询，同一代读取循环不重复发起请求，
关闭/切换时取消请求，失败时退避重试。HTTP/2 仍有流数和服务端处理能力限制，
移除前端队列不代表无限容量。如果实际协商到 HTTP/1.1，浏览器仍会自行排队；
因此需确认 terminal/read 等实际业务请求显示 h2，不能只看独立连接测试成功。

## 替换证书与旧配置升级

不能恢复明文 HTTP。旧外置配置中的 `server.ssl.enabled: false` 必须改为 `true`，
并移除 `SERVER_SSL_ENABLED=false` 或 `--server.ssl.enabled=false` 等覆盖值。
设置页只保留自动管理与自定义证书两种模式，不允许写入关闭 HTTPS 的配置。

部署远程服务时，请配置自己的证书：

```yaml
server:
  http2:
    enabled: true
  ssl:
    enabled: true
    auto-generate: false
    key-store: file:/path/to/server.p12
    key-store-type: PKCS12
    key-store-password: ${SERVER_SSL_KEY_STORE_PASSWORD}
    key-alias: server
```

也可以通过 `LOCAL_TLS_AUTO_GENERATE=false` 关闭自动管理。
自定义 `key-store` 地址始终交给 Spring Boot 加载；文件缺失或密码错误时启动报错，
不会生成自签证书替代正式证书。自动管理只处理数据目录下的 `tls/local-backend.p12`，
若在此默认位置放置自己的证书，也必须关闭自动管理并自行配置密码等字段。
设置 `server.ssl.certificate` 或 `server.ssl.bundle` 时，自动生成器让位，并清除 YAML
继承的默认 `key-store` 值以避免混用；自定义的非默认 `key-store` 配置不会被自动清除。
远程服务使用域名匹配且受信任的证书；不会因本机的自签证书例外放行远程证书。

如果由 Nginx/Caddy 提供公网 HTTPS，代理到 Java 的上游也必须是 HTTPS。
Java 仍需一套服务端证书，可以使用自定义正式证书或内部 CA 签发的证书；同机回环上游
也可以使用本机生成的自签证书，但代理必须显式信任其公开证书并验证主机名。
公网证书可由代理单独管理，不要求与 Java 共享私钥。

同机 Nginx 上游示例（下面只是转发部分，公网监听端的证书配置保持原有配置）：

```nginx
location / {
    proxy_pass https://127.0.0.1:8889;
    proxy_ssl_verify on;
    proxy_ssl_trusted_certificate /path/to/data/tls/local-backend.crt;
    proxy_ssl_server_name on;
    proxy_ssl_name localhost;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_buffering off;
    proxy_read_timeout 300s;
}
```

将端口和公开证书路径替换为实际值。不同主机时不要使用仅包含 localhost 的证书，
应给 Java 配置匹配上游域名的证书，并让 Nginx/Caddy 信任该签发机构。
自签证书续期后也需更新代理的信任文件并重载。不能用关闭证书验证来代替信任配置。
HTTP/2 仍取决于每一段的协议协商；HTTPS 不等于强制 HTTP/2，上游 HTTP/1.1 over TLS
不影响客户端到 Nginx 使用 HTTP/2。

参考：[Spring Boot SSL / HTTP/2](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.configure-http2)、
[WebView2 证书事件](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2_14)。
