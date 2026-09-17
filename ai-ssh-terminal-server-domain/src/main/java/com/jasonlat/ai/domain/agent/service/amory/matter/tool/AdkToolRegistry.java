package com.jasonlat.ai.domain.agent.service.amory.matter.tool;

import com.google.adk.tools.BaseTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class AdkToolRegistry {

    /** Spring Bean 名称 -> 工具提供者。 */
    private final Map<String, AdkToolProvider> providers;

    public AdkToolRegistry(Map<String, AdkToolProvider> providers) {
        this.providers = providers;
    }

    public List<Object> getAllTools() {
        List<Object> tools = new ArrayList<>();
        for (Map.Entry<String, AdkToolProvider> entry : providers.entrySet()) {
            addProviderTools(tools, entry.getKey(), entry.getValue());
        }
        return tools;
    }

    /**
     * 根据 Spring Bean 名称获取指定工具。
     * 可传单个名称，也可直接传入 String 数组；返回顺序与参数顺序一致。
     * 示例：
     * getAllTools("sshExecuteAdkTool");
     * getAllTools(new String[]{"sshExecuteAdkTool", "fileAdkTool"});
     */
    public List<Object> getAllTools(String... beanNames) {
        if (beanNames == null || beanNames.length == 0) {
            return getAllTools();
        }
        List<Object> tools = new ArrayList<>();
        Set<String> uniqueBeanNames = new LinkedHashSet<>();

        for (String beanName : beanNames) {
            if (beanName == null || beanName.isBlank()) {
                throw new IllegalArgumentException("ADK 工具 Bean 名称不能为空");
            }
            uniqueBeanNames.add(beanName);
        }
        for (String beanName : uniqueBeanNames) {
            AdkToolProvider provider = providers.get(beanName);
            if (provider == null) {
                throw new IllegalArgumentException("未找到 ADK 工具 Bean: " + beanName + "，可用 Bean: " + providers.keySet());
            }
            addProviderTools(tools, beanName, provider);
        }
        return tools;
    }

    private void addProviderTools(List<Object> tools, String beanName, AdkToolProvider provider) {
        try {
            List<? extends BaseTool> providerTools = provider.getTools();
            tools.addAll(providerTools);
            log.info("注册 ADK 工具成功 beanName={}, provider={}, count={}", beanName, provider.getClass().getSimpleName(), providerTools.size());
        } catch (Exception e) {
            throw new IllegalStateException("注册 ADK 工具失败: " + beanName, e);
        }
    }
}
