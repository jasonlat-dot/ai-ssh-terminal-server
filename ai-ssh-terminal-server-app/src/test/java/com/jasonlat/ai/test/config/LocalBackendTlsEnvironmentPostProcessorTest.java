package com.jasonlat.ai.test.config;

import com.jasonlat.ai.config.LocalBackendTlsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 Windows ACL 和 keytool，覆盖安装版首次启动与旧版错误权限恢复。 */
@EnabledOnOs(OS.WINDOWS)
class LocalBackendTlsEnvironmentPostProcessorTest {
    @TempDir
    Path dataDirectory;

    @Test
    void generatesAndReusesCertificateWithoutRelyingOnJavaUserName() throws Exception {
        String previousName = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "ai-ssh-nonexistent-account-for-test");
            var first = start();
            Path store = dataDirectory.resolve("tls/local-backend.p12");
            byte[] originalStore = Files.readAllBytes(store);
            String password = first.getProperty("server.ssl.key-store-password");
            assertTrue(Files.isRegularFile(dataDirectory.resolve("tls/local-backend.crt")));
            assertEquals("PKCS12", first.getProperty("server.ssl.key-store-type"));
            var second = start();
            assertEquals(password, second.getProperty("server.ssl.key-store-password"));
            assertArrayEquals(originalStore, Files.readAllBytes(store));
        } finally {
            System.setProperty("user.name", previousName);
        }
    }

    @Test
    void repairsOldDirectoryAndFileAclsWithoutReplacingCertificate() throws Exception {
        var first = start();
        Path tls = dataDirectory.resolve("tls");
        Path store = tls.resolve("local-backend.p12");
        byte[] originalStore = Files.readAllBytes(store);
        String password = first.getProperty("server.ssl.key-store-password");
        // 用不属于当前进程的主体模拟旧版写入了错误 SID 的情况。
        var system = tls.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
        for (String name : List.of("generation.lock", "keystore.password", "local-backend.p12", "local-backend.crt")) {
            Files.getFileAttributeView(tls.resolve(name), AclFileAttributeView.class).setAcl(List.of(
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
        Files.getFileAttributeView(tls, AclFileAttributeView.class).setAcl(List.of(
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));

        var recovered = start();
        assertEquals(password, recovered.getProperty("server.ssl.key-store-password"));
        assertArrayEquals(originalStore, Files.readAllBytes(store));
        assertTrue(Files.isRegularFile(tls.resolve("local-backend.crt")));
    }

    private StandardEnvironment start() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.config.data-directory", dataDirectory.toString(),
                "server.ssl.enabled", true,
                "server.ssl.auto-generate", true)));
        new LocalBackendTlsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }
}
