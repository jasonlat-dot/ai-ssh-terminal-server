package com.jasonlat.ai.config;

import com.jasonlat.ai.config.properties.ChatAttachmentProperties;
import com.jasonlat.ai.domain.agent.model.valobj.ChatAttachmentPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatAttachmentProperties.class)
public class ChatAttachmentConfiguration {
    @Bean
    public ChatAttachmentPolicy chatAttachmentPolicy(ChatAttachmentProperties properties) {
        return new ChatAttachmentPolicy(properties.getMaxFiles(), properties.getMaxTotalSize().toBytes(),
                properties.getMaxTextChars(), properties.getMaxConcurrentRequests(), properties.isAllowAnonymousFiles());
    }
}
