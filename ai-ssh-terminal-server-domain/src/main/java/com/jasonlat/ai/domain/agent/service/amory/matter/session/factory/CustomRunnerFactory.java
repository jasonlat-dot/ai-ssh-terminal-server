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

/**
 * 统一创建项目使用的 ADK {@link Runner}。
 *
 * <p>所有 Runner 必须使用同一套自定义 Session/Memory 策略，否则部分 Agent 会重新启用
 * ADK 默认历史管理，造成业务历史与框架历史并存。SessionService 虽是单例，但内部按照
 * appName/userId/sessionId 三级键隔离会话。</p>
 *
 * <p>ArtifactService 当前使用内存实现，适合本项目以文本和 SSH 工具为主的运行模式；
 * 它与 ConversationContextStore 的业务会话状态不是同一类数据。</p>
 */
@Component
public class CustomRunnerFactory {

    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private CustomAdkMemoryService customAdkMemoryService;

    /**
     * 创建带插件链的 Runner。插件顺序沿用调用方传入顺序，由 ADK 依次触发回调。
     *
     * @param baseAgent Runner 的根 Agent
     * @param appName ADK Session 的应用隔离键
     * @param plugins 需要挂载的插件列表
     */
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

    /**
     * 创建不带插件的 Runner，Session 与 Memory 行为和带插件重载保持一致。
     *
     * @param baseAgent Runner 的根 Agent
     * @param appName ADK Session 的应用隔离键
     */
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
