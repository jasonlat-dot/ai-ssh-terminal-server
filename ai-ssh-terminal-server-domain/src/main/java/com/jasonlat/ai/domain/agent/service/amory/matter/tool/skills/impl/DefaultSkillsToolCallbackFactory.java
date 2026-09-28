package com.jasonlat.ai.domain.agent.service.amory.matter.tool.skills.impl;

import com.jasonlat.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.skills.SkillsToolCallbackFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SkillsTool 默认创建工厂。
 *
 * 所有目录统一添加到同一个 SkillsTool 中，避免多个 SkillsTool
 * 都使用名称“Skill”而产生工具名称冲突。
 */
@Service
public class DefaultSkillsToolCallbackFactory implements SkillsToolCallbackFactory {

    /** 已经解析过的 classpath Skills 根目录，避免多个 Agent 重复解压相同资源。 */
    private final Map<String, String> extractedResourceDirectories = new HashMap<>();

    @Override
    public Optional<ToolCallback> create(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> configurations) {

        if (configurations == null || configurations.isEmpty()) {
            return Optional.empty();
        }

        SkillsTool.Builder builder = SkillsTool.builder();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills config : configurations) {

            if (config == null) {
                continue;
            }

            String rootPath = config.getRootPath();
            if (!StringUtils.hasText(rootPath)) {
                throw new IllegalArgumentException("Skills root-path 不能为空");
            }

            String type = StringUtils.hasText(config.getType()) ? config.getType().trim() : "resource";

            switch (type) {
                /*
                 * SkillsTool 0.4.2 的 addSkillsResource() 内部会调用 Resource#getFile()。
                 * 开发环境中的 classpath 是普通目录，因此可以正常工作；打成 Spring Boot JAR 后，
                 * classpath 资源位于嵌套 JAR 中，不再对应一个真实磁盘目录。这里先尝试直接加载，
                 * 如果资源没有真实文件路径，则完整解压到临时目录，再按磁盘目录交给 SkillsTool。
                 */
                case "resource" -> addClasspathSkills(builder, rootPath);
                case "directory" -> builder.addSkillsDirectory(rootPath);
                default -> throw new IllegalArgumentException("不支持的 Skills 类型: " + type);
            }
        }

        return Optional.of(builder.build());
    }

    /**
     * 添加 classpath 中的 Skills，并兼容 IDE 目录和 Spring Boot 嵌套 JAR 两种运行方式。
     *
     * @param builder  SkillsTool 构建器
     * @param rootPath classpath 中的 Skills 根路径
     */
    private void addClasspathSkills(SkillsTool.Builder builder, String rootPath) {
        ClassPathResource classPathResource = new ClassPathResource(normalizeRootPath(rootPath));

        try {
            // IDE 或展开目录运行时，资源本身就是磁盘目录，可直接交给第三方组件读取。
            builder.addSkillsDirectory(classPathResource.getFile().getAbsolutePath());
        } catch (IOException exception) {
            // 可执行 JAR 中没有真实目录，需要先把目录下的所有资源释放到临时文件系统。
            builder.addSkillsDirectory(extractClasspathDirectory(rootPath));
        }
    }

    /**
     * 将 classpath 目录递归解压到临时目录，并返回可供 SkillsTool 使用的磁盘路径。
     *
     * @param rootPath classpath 根路径
     * @return 解压后的绝对目录
     */
    private synchronized String extractClasspathDirectory(String rootPath) {
        String normalizedRoot = normalizeRootPath(rootPath);
        String cachedDirectory = extractedResourceDirectories.get(normalizedRoot);
        if (cachedDirectory != null) {
            return cachedDirectory;
        }

        try {
            Path outputDirectory = Files.createTempDirectory("ai-ssh-terminal-skills-").toAbsolutePath().normalize();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:" + normalizedRoot + "/**");
            int copiedFiles = 0;

            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null) {
                    continue;
                }

                /*
                 * Resource#getURL() 在普通目录和嵌套 JAR 中都能工作。截取根目录之后的相对路径，
                 * 再进行 URL 解码，以正确处理空格、中文等文件名。
                 */
                String externalPath = resource.getURL().toExternalForm();
                String marker = normalizedRoot + "/";
                int markerIndex = externalPath.lastIndexOf(marker);
                if (markerIndex < 0) {
                    continue;
                }

                String encodedRelativePath = externalPath.substring(markerIndex + marker.length());
                String relativePath = URLDecoder.decode(encodedRelativePath, StandardCharsets.UTF_8);
                Path targetFile = outputDirectory.resolve(relativePath).normalize();

                // 拒绝任何越过临时根目录的异常路径，防止资源名造成路径穿越。
                if (!targetFile.startsWith(outputDirectory)) {
                    throw new IllegalStateException("Skills 资源路径越界: " + relativePath);
                }

                Files.createDirectories(targetFile.getParent());
                try (InputStream inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                copiedFiles++;
            }

            if (copiedFiles == 0) {
                throw new IllegalStateException("classpath 中未找到 Skills 文件: " + normalizedRoot);
            }

            String extractedDirectory = outputDirectory.toString();
            extractedResourceDirectories.put(normalizedRoot, extractedDirectory);
            return extractedDirectory;
        } catch (IOException exception) {
            throw new IllegalStateException("无法释放 classpath Skills 目录: " + normalizedRoot, exception);
        }
    }

    /** 去除 classpath 路径首尾多余的斜杠，确保资源匹配表达式稳定。 */
    private String normalizeRootPath(String rootPath) {
        String normalized = rootPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
