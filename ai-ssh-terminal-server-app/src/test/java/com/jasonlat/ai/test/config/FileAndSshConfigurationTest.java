package com.jasonlat.ai.test.config;

import com.jasonlat.ai.config.FileServiceConfiguration;
import com.jasonlat.ai.config.SshInfrastructureConfiguration;
import com.jasonlat.ai.config.properties.FileUploadProperties;
import com.jasonlat.ai.domain.file.adapter.port.ObjectStorageResolver;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadPolicy;
import com.jasonlat.ai.infrastructure.adapter.port.storage.minio.MinioObjectStorage;
import com.jasonlat.ai.infrastructure.model.settings.MinioStorageSettings;
import com.jasonlat.ai.infrastructure.model.settings.SshCommandSettings;
import com.jasonlat.ai.infrastructure.model.settings.SshHttpProxySettings;
import com.jasonlat.ai.infrastructure.model.settings.TerminalSessionSettings;
import com.jasonlat.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 只启动配置装配上下文，不连接数据库、SSH 或 MinIO。 */
class FileAndSshConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FileServiceConfiguration.class, SshInfrastructureConfiguration.class)
            .withBean(MinioObjectStorage.class);

    @Test
    void missingStorageStillStartsAndUploadResolutionReturnsBusinessError() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageResolver.class)
                    .hasSingleBean(FileUploadPolicy.class).hasSingleBean(SshCommandSettings.class);
            assertThat(context.getBean(SshCommandSettings.class).idleTimeoutSeconds()).isEqualTo(10);
            assertThat(context.getBean(TerminalSessionSettings.class).maxTotalSessions()).isEqualTo(500);
            assertThat(context.getBean(SshHttpProxySettings.class).enabled()).isFalse();
            ObjectStorageResolver resolver = context.getBean(ObjectStorageResolver.class);
            assertThat(assertThrows(AppException.class, resolver::defaultStorage).getCode())
                    .isEqualTo("FILE_STORAGE_NOT_CONFIGURED");
        });
    }

    @Test
    void bindsExistingKeysAndCreatesImmutableSnapshots() {
        contextRunner.withPropertyValues(
                "ai.ssh.command.idle-timeout-seconds=23",
                "ai.ssh.command.max-execution-timeout-seconds=900",
                "ai.ssh.terminal.max-total-sessions=321",
                "ai.ssh.terminal.idle-timeout-minutes=12",
                "ai.ssh.http-proxy.enabled=true",
                "ai.ssh.http-proxy.host=proxy.example.com",
                "ai.ssh.http-proxy.port=7890",
                "ai.file.upload.max-file-size=7MB",
                "ai.file.upload.max-concurrent-uploads=2",
                "ai.file.upload.allowed-extensions[0]=txt",
                "ai.file.upload.allowed-extensions[1]=log",
                "ai.file.storage.minio.storage-id=archive",
                "ai.file.storage.minio.read-timeout=12s"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SshCommandSettings.class).idleTimeoutSeconds()).isEqualTo(23);
            assertThat(context.getBean(SshCommandSettings.class).maxExecutionTimeoutSeconds()).isEqualTo(900);
            assertThat(context.getBean(TerminalSessionSettings.class).maxTotalSessions()).isEqualTo(321);
            assertThat(context.getBean(TerminalSessionSettings.class).idleTimeoutMinutes()).isEqualTo(12);
            assertThat(context.getBean(SshHttpProxySettings.class).host()).isEqualTo("proxy.example.com");
            FileUploadPolicy policy = context.getBean(FileUploadPolicy.class);
            assertThat(policy.maxFileSizeBytes()).isEqualTo(7L * 1024 * 1024);
            assertThat(policy.maxConcurrentUploads()).isEqualTo(2);
            assertThat(policy.allowedExtensions()).containsExactlyInAnyOrder("txt", "log");
            assertThrows(UnsupportedOperationException.class, () -> policy.allowedExtensions().add("exe"));
            context.getBean(FileUploadProperties.class).setMaxConcurrentUploads(10);
            assertThat(policy.maxConcurrentUploads()).isEqualTo(2);
            MinioStorageSettings minio = context.getBean(MinioStorageSettings.class);
            assertThat(minio.storageId()).isEqualTo("archive");
            assertThat(minio.readTimeout()).isEqualTo(Duration.ofSeconds(12));
        });
    }

    @Test
    void incompleteMinioConfigurationFailsOnUseInsteadOfStartup() {
        contextRunner.withPropertyValues("ai.file.storage.minio.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            ObjectStorageResolver resolver = context.getBean(ObjectStorageResolver.class);
            assertThat(assertThrows(AppException.class, resolver::defaultStorage).getCode())
                    .isEqualTo("FILE_STORAGE_CONFIG_INVALID");
        });
    }

    @Test
    void invalidCommandTimeoutStillFailsConfigurationValidation() {
        contextRunner.withPropertyValues("ai.ssh.command.idle-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
