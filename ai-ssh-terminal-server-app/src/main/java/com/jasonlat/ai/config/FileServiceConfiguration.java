package com.jasonlat.ai.config;

import com.jasonlat.ai.domain.file.service.storage.resolver.DefaultIObjectStorageResolver;
import com.jasonlat.ai.domain.file.service.storage.resolver.IObjectStorageResolver;
import com.jasonlat.ai.config.properties.FileStorageProperties;
import com.jasonlat.ai.config.properties.FileUploadProperties;
import com.jasonlat.ai.domain.file.service.IObjectStorageService;
import com.jasonlat.ai.domain.file.model.valobj.FileUploadPolicy;
import com.jasonlat.ai.domain.file.model.valobj.LocalFileStorageSettings;
import com.jasonlat.ai.domain.file.model.valobj.MinioStorageSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 文件上传配置的绑定与转换集中在启动模块，不把 app 类型传入下层。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FileStorageProperties.class, FileUploadProperties.class})
public class FileServiceConfiguration {

    @Bean
    public FileUploadPolicy fileUploadPolicy(FileUploadProperties properties) {
        return new FileUploadPolicy(properties.getMaxFileSize().toBytes(),
                properties.getMaxConcurrentUploads(), properties.getDownloadUrlTtl(),
                properties.getAllowedExtensions());
    }

    @Bean
    public MinioStorageSettings minioStorageSettings(FileStorageProperties properties) {
        FileStorageProperties.Minio minio = properties.getMinio();
        return new MinioStorageSettings(minio.isEnabled(), minio.getStorageId(), minio.getEndpoint(),
                minio.getPublicEndpoint(), minio.getAccessKey(), minio.getSecretKey(), minio.getBucket(),
                minio.getRegion(), minio.getConnectTimeout(), minio.getReadTimeout(),
                minio.getWriteTimeout(), minio.getCallTimeout());
    }

    /**
     * 把可变的 Spring 配置对象转换为领域层不可变快照。
     * 本地目录只传递给存储适配器，不会通过上传响应返回给前端。
     */
    @Bean
    public LocalFileStorageSettings localFileStorageSettings(FileStorageProperties properties) {
        FileStorageProperties.LocalFile local = properties.getLocal();
        return new LocalFileStorageSettings(local.isEnabled(), local.getStorageId(),
                local.getRootDirectory(), local.getPublicBaseUrl(), local.getSigningSecret());
    }

    /** app 提供配置和已注册实现，按当前结构装配领域层存储选择器。 */
    @Bean
    public IObjectStorageResolver objectStorageResolver(FileStorageProperties properties, List<IObjectStorageService> ports) {
        return new DefaultIObjectStorageResolver(properties.getDefaultId(), ports);
    }
}
