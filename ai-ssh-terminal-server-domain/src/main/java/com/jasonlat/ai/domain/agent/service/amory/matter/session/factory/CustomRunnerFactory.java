package com.jasonlat.ai.domain.agent.service.amory.matter.session.factory;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkMemoryService;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

@Component
public class CustomRunnerFactory {

    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private CustomAdkMemoryService customAdkMemoryService;

    public Runner create(BaseAgent baseAgent, String appName, List<BasePlugin> plugins) {

        return Runner.builder()
                .agent(baseAgent)
                .appName(appName)
                .artifactService(new InMemoryArtifactService())
                .sessionService(customAdkSessionService)
                .memoryService(customAdkMemoryService)
                .plugins(plugins)
                .build();

    }

    public Runner create(BaseAgent baseAgent, String appName) {

        return Runner.builder()
                .agent(baseAgent)
                .appName(appName)
                .artifactService(new InMemoryArtifactService())
                .sessionService(customAdkSessionService)
                .memoryService(customAdkMemoryService)
                .build();

    }
}