package com.jasonlat.ai.domain.agent.service.amory.matter.session.factory;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.jasonlat.ai.domain.agent.service.amory.matter.plugin.SystemOneToolSelectionPlugin;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkMemoryService;
import com.jasonlat.ai.domain.agent.service.amory.matter.session.CustomAdkSessionService;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
     * 每轮模型请求工具筛选插件。
     *
     * <p>由 Runner 工厂统一挂载，保证主 Agent、单子 Agent 和批量子 Agent
     * 不依赖 YAML 中是否显式声明插件，都能获得相同的 Jev/Laya 筛选能力。</p>
     */
    @Resource
    private SystemOneToolSelectionPlugin systemOneToolSelectionPlugin;

    /**
     * 创建带插件链的 Runner。系统插件和调用方插件合并后按 {@link Ordered#getOrder()}
     * 从小到大执行；顺序相同的插件保持调用方传入的相对顺序。
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
                .plugins(withMandatoryPlugins(plugins))
                .build();

    }

    /**
     * 创建不带插件的 Runner，Session 与 Memory 行为和带插件重载保持一致。
     *
     * @param baseAgent Runner 的根 Agent
     * @param appName ADK Session 的应用隔离键
     */
    public Runner create(BaseAgent baseAgent, String appName) {

        return create(baseAgent, appName, List.of());

    }

    /**
     * 合并系统级必需插件与业务插件，同时避免调用方重复传入同一插件。
     *
     * <p>合并完成后根据插件自己的 order 稳定排序。工具筛选插件 order 为 100，
     * 日志插件 order 为 1000，因此日志读取到的是实际将发送给模型的工具集合。
     * 未实现 {@link Ordered} 的插件使用最低优先级，并保持原始相对顺序。</p>
     *
     * @param plugins 调用方通过 Agent 配置声明的业务插件
     * @return 包含系统工具筛选插件的不可变有序列表
     */
    private List<BasePlugin> withMandatoryPlugins(List<BasePlugin> plugins) {
        List<BasePlugin> merged = new ArrayList<>();
        merged.add(systemOneToolSelectionPlugin);

        if (plugins != null) {
            plugins.stream()
                    .filter(Objects::nonNull)
                    .filter(plugin -> !(plugin instanceof SystemOneToolSelectionPlugin))
                    .forEach(merged::add);
        }

        /*
         * List.sort 使用稳定排序。两个插件 order 相同时，不会打乱 YAML 中声明的先后关系。
         */
        merged.sort(Comparator.comparingInt(this::getPluginOrder));

        return List.copyOf(merged);
    }

    /**
     * 获取插件声明的执行顺序。
     *
     * <p>采用 Spring 标准 {@link Ordered} 接口，避免工厂依赖具体插件类型。
     * 新插件只要实现 Ordered 并提供 {@code getOrder()}，即可参与排序；没有声明顺序的
     * 第三方或旧插件统一放在有序插件之后。</p>
     *
     * @param plugin 待装配的 ADK 插件
     * @return 插件 order；未实现 Ordered 时返回 {@link Ordered#LOWEST_PRECEDENCE}
     */
    private int getPluginOrder(BasePlugin plugin) {
        return plugin instanceof Ordered ordered
                ? ordered.getOrder()
                : Ordered.LOWEST_PRECEDENCE;
    }
}
