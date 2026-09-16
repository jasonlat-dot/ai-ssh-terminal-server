package com.jasonlat.ai.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SSH 命令安全策略配置。
 *
 * <p>内置拒绝规则不会被该配置覆盖；这里只允许业务侧追加拒绝规则。例如可以
 * 针对生产环境增加“禁止删除 Kubernetes 命名空间”或“禁止清空业务数据库”等规则。</p>
 *
 * <p>配置前缀：{@code ai.ssh.command-safety}</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.ssh.command-safety")
public class SshCommandSafetyProperties {

    /** 单条命令最大长度，防止超长或高度混淆的命令进入执行通道。 */
    private int maxCommandLength = 8192;

    /**
     * 业务侧追加的拒绝规则。
     * 列表默认为空，追加规则在内置规则之后匹配。
     */
    private List<DenyRule> additionalDenyRules = new ArrayList<>();

    @Data
    public static class DenyRule {

        /** 稳定且唯一的规则编号，用于日志检索和前端展示。 */
        private String id;

        /** 用户可读的拦截原因。 */
        private String description;

        /**
         * Java 正则表达式，对规范化后的整条命令执行 find()。
         * 因此通常不需要在表达式两端添加 .*。
         */
        private String pattern;

        /** 是否忽略大小写，默认开启。 */
        private boolean caseInsensitive = true;
    }
}
