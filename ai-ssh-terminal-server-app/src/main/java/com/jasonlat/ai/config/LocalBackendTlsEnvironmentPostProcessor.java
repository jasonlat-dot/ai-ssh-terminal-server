package com.jasonlat.ai.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 为默认本机后端准备独立的自签证书，在 Tomcat 创建 HTTPS 连接器之前注入 SSL 配置。
 *
 * <p>不把共享私钥或固定密码打进安装包；证书仅适用于 localhost 和回环 IP。
 * Windows 桌面客户端以公钥证书内容校验信任，不向系统根证书库添加证书。
 * 仅在启用自动管理时准备统一数据目录下的默认证书；自定义 key-store、PEM 或 SSL bundle
 * 不经过自动生成流程，避免正式证书路径写错时被自签证书替代。</p>
 */
public final class LocalBackendTlsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    /** 私钥条目名称；固定名称不代表私钥共享，每个数据目录都有独立密钥。 */
    private static final String ALIAS = "local-backend";
    /** 密码只通过子进程环境传递，不能出现在 keytool 命令行或普通日志中。 */
    private static final String PASSWORD_ENV = "AI_SSH_LOCAL_KEYSTORE_PASSWORD";
    /** 应用自定义 SSL 开关，由本处理器读取；控制生成、复用及续期，不是 Spring Boot 内置参数。 */
    private static final String AUTO_GENERATE_PROPERTY = "server.ssl.auto-generate";

    /**
     * 必须在 Spring Boot 读取 application*.yml 之后运行，以尊重配置目录和自定义证书。
     *
     * @return 配置加载完成后的执行顺序
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Tomcat 启动前检查配置中的默认证书地址，准备完成后补充私钥库密码等参数。
     *
     * <p>key-store 非空不再直接退出：默认地址由本类自动管理，自定义地址仍交给 Spring Boot。
     * 自动管理关闭时不创建目录、不生成证书、不读取随机密码，完全使用用户的 SSL 配置。</p>
     *
     * @param environment 已加载配置文件、系统属性和环境变量的 Spring 环境
     * @param application 当前启动的应用，接口要求传入，本实现不改变启动流程
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // HTTPS 是服务约束，不再是可关闭的功能。旧 YAML、环境变量或命令行试图关闭时
        // 拒绝启动，而不是静默忽略配置；反向代理也必须通过 TLS 连接 Java。
        if (!environment.getProperty("server.ssl.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Java 后端必须使用 HTTPS，不允许 server.ssl.enabled=false；"
                    + "请移除关闭 SSL 的配置或环境变量，Nginx/Caddy 上游也必须使用 HTTPS");
        }
        // 在所有证书分支之前发布强制值。自定义 key-store、PEM、bundle 提前返回时
        // 也不能遗漏 TLS；本属性只固定协议，不覆盖用户的证书、密码或证书管理模式。
        environment.getPropertySources().addFirst(new MapPropertySource("requiredBackendHttps",
                Map.of("server.ssl.enabled", true)));

        // 占位符由 Spring 解析；保留兜底，使旧版外置 YAML 没有新配置时也能使用原有证书。
        Path directory = Path.of(environment.getProperty("app.config.data-directory",
                Path.of(System.getProperty("user.home"), ".ai-ssh-terminal").toString()))
                .toAbsolutePath().normalize().resolve("tls");
        // 自动生成的自签证书文件完整路径，如：/home/jasonlat/.ai-ssh-terminal/tls/local-backend.p12
        Path defaultStore = directory.resolve("local-backend.p12");
        // 读取 yml 中用户手动配置的证书
        String configuredStore = environment.getProperty("server.ssl.key-store");
        boolean usesDefaultStore = !hasText(environment, "server.ssl.key-store")
                || isDefaultStore(configuredStore, defaultStore);

        if (hasText(environment, "server.ssl.certificate") || hasText(environment, "server.ssl.bundle")) {
            // YAML 已给 key-store 设置默认地址；选用 PEM/bundle 时移除该默认值，避免混用两套配置。
            // 真正自定义的 key-store 不清除，让 Spring Boot 检查用户提供的配置，而不是悄悄改写。
            if (usesDefaultStore && hasText(environment, "server.ssl.key-store")) {
                environment.getPropertySources().addFirst(new MapPropertySource("localBackendTlsDefaults",
                        Map.of("server.ssl.key-store", "")));
            }
            return;
        }

        // 自动管理仅作用于内置默认路径；绝不因自定义正式证书不存在而生成自签替代品。
        if (!environment.getProperty(AUTO_GENERATE_PROPERTY, Boolean.class, true) || !usesDefaultStore) {
            return;
        }
        try {
            Files.createDirectories(directory);
            restrictPermissions(directory, "rwx------");
            // 同一数据目录只能有一个进程生成/续期证书，避免密码与私钥文件不匹配。
            try (FileChannel channel = FileChannel.open(directory.resolve("generation.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                configureCertificate(environment, directory);
            }
        } catch (Exception exception) {
            // 不能悄悄回退到明文 HTTP，否则客户端会与后端实际协议不一致。
            throw new IllegalStateException("无法准备本机 HTTPS 证书，目录=" + directory
                    + "；请检查目录权限和 Java runtime/bin/keytool 是否存在", exception);
        }
    }

    /**
     * 判断配置的文件地址是否指向应用管理的默认证书，不要求该文件已存在。
     *
     * <p>使用 Spring 的资源路径解析，兼容 file:/C:/...、file:C:/...、空格编码和普通文件路径。
     * classpath、网络 URL 或无法解析的路径均不属于自动管理范围；不主动读取或创建这些资源。</p>
     *
     * @param location 用户配置并已解析占位符的 key-store 地址
     * @param defaultStore 统一数据目录下允许自动管理的私钥库绝对路径
     * @return 地址规范化后与默认路径一致时返回 true，否则交由 Spring Boot 加载自定义证书
     */
    private boolean isDefaultStore(String location, Path defaultStore) {
        try {
            return ResourceUtils.getFile(location).toPath().toAbsolutePath().normalize().equals(defaultStore);
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 重用证书或在首次启动/临近过期时生成新证书，最后发布 Spring SSL 配置。
     *
     * @param environment 待补充 SSL 密钥配置的环境
     * @param directory 统一数据根目录下的 tls 目录
     * @throws Exception 文件、密钥库、证书解析或 keytool 执行失败
     */
    private void configureCertificate(ConfigurableEnvironment environment, Path directory) throws Exception {
        Path store = directory.resolve("local-backend.p12");
        Path passwordFile = directory.resolve("keystore.password");
        if (Files.exists(store) && !Files.isRegularFile(passwordFile)) {
            // 丢失密码不应覆盖现有私钥；用户需显式备份/移走损坏的 TLS 文件后重建。
            throw new IOException("已有密钥库但缺少 keystore.password");
        }
        if (!Files.exists(passwordFile)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            Files.writeString(passwordFile, Base64.getUrlEncoder().withoutPadding().encodeToString(random),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        restrictPermissions(passwordFile, "rw-------");
        String password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim();
        if (password.length() < 16) {
            throw new IOException("本机密钥库密码文件无效");
        }

        X509Certificate certificate = Files.isRegularFile(store) ? readCertificate(store, password) : null;
        if (certificate == null || certificate.getNotAfter().toInstant()
                .isBefore(Instant.now().plus(30, ChronoUnit.DAYS))) {
            generateStore(directory, store, password);
            certificate = readCertificate(store, password);
        }
        restrictPermissions(store, "rw-------");

        // 仅导出公开的证书供桌面端固定信任；不导出私钥，不写入系统证书库。
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Path publicCertificate = directory.resolve("local-backend.crt");
        if (!Files.exists(publicCertificate) || !pem.equals(Files.readString(publicCertificate))) {
            Path temporary = Files.createTempFile(directory, "certificate-", ".crt");
            try {
                Files.writeString(temporary, pem, StandardCharsets.US_ASCII);
                replaceFile(temporary, publicCertificate);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        // 默认地址已在 YAML 中声明；补充规范化后的文件 URI、类型、别名及本机随机密码。
        // 全部参数在 Tomcat 绑定 SSL 配置之前准备完成，不把随机密码回写到 YAML。
        environment.getPropertySources().addFirst(new MapPropertySource("localBackendTls", Map.of(
                "server.ssl.enabled", true,
                "server.ssl.key-store", store.toUri().toString(),
                "server.ssl.key-store-type", "PKCS12",
                "server.ssl.key-store-password", password,
                "server.ssl.key-alias", ALIAS)));
    }

    /**
     * 通过当前 Java 运行时的标准 keytool 生成临时密钥库，成功后替换默认密钥库。
     *
     * @param directory TLS 工作目录
     * @param store 生成完成后的密钥库位置
     * @param password 随机密码，仅注入 keytool 子进程环境
     * @throws Exception 运行时缺少 keytool、执行超时或文件操作失败
     */
    private void generateStore(Path directory, Path store, String password) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(keytool)) {
            throw new IOException("Java 运行时缺少 keytool: " + keytool);
        }
        Path temporary = Files.createTempFile(directory, "keystore-", ".p12");
        Path output = Files.createTempFile(directory, "keytool-", ".log");
        try {
            // keytool 要求新密钥库路径不存在；只删除刚由本方法创建的空临时文件。
            Files.delete(temporary);
            List<String> arguments = new ArrayList<>(List.of(keytool.toString(), "-genkeypair",
                    "-noprompt", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                    "-sigalg", "SHA256withRSA", "-validity", "365", "-storetype", "PKCS12",
                    "-keystore", temporary.toString(), "-storepass:env", PASSWORD_ENV,
                    "-dname", "CN=localhost, OU=Local Backend, O=Agent SSH",
                    "-ext", "SAN=DNS:localhost,IP:127.0.0.1,IP:::1",
                    "-ext", "BC=ca:false", "-ext", "EKU=serverAuth", "-ext", "KU=digitalSignature,keyEncipherment"));
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put(PASSWORD_ENV, password);
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
            if (!completed) {
                process.destroyForcibly().waitFor();
                throw new IOException("生成本机 HTTPS 证书超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("keytool 生成证书失败，exit=" + process.exitValue());
            }
            readCertificate(temporary, password); // 先验证可读，再替换已有文件。
            restrictPermissions(temporary, "rw-------");
            replaceFile(temporary, store);
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(output);
        }
    }

    /** 读取默认私钥条目的证书；密码不正确/文件损坏时拒绝启动，不自动覆盖。 */
    private X509Certificate readCertificate(Path store, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, password.toCharArray());
        }
        if (!keyStore.isKeyEntry(ALIAS) || !(keyStore.getCertificate(ALIAS) instanceof X509Certificate certificate)) {
            throw new IOException("默认本机密钥库缺少私钥证书条目");
        }
        return certificate;
    }

    /** 同目录原子替换；不支持原子移动的文件系统仍保留普通替换能力。 */
    private void replaceFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 私钥目录只允许当前运行用户访问，避免自定义的共享数据根目录泄漏私钥。 */
    private void restrictPermissions(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view != null) {
                var user = path.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
                var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class));
                if (Files.isDirectory(path)) {
                    entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
                }
                // 只调整自动生成的 tls 目录及默认私钥文件，不修改整个数据目录或用户自定义证书。
                view.setAcl(List.of(entry.build()));
            }
        }
    }

    /** 判断是否已有非空的自定义证书配置。 */
    private boolean hasText(ConfigurableEnvironment environment, String key) {
        String value = environment.getProperty(key);
        return value != null && !value.isBlank();
    }
}
