package com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh.security.impl;

import com.jasonlat.ai.domain.agent.model.valobj.properties.SshCommandSafetyProperties;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh.security.CommandSafetyDecision;
import com.jasonlat.ai.domain.agent.service.amory.matter.tool.ssh.security.CommandSafetyPolicy;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 默认 SSH 命令安全策略。
 *
 * <p>内置规则用于保护主机文件系统、块设备和服务可用性，始终生效；
 * application.yml 只能通过 additional-deny-rules 追加规则，不能删除内置规则。</p>
 *
 * <p>判定流程：</p>
 * <pre>
 * 原始命令
 *   -> normalize：统一 Unicode、移除 NUL、合并续行和空白
 *   -> 校验最大长度
 *   -> 按顺序匹配内置拒绝规则
 *   -> 匹配业务追加的拒绝规则
 *   -> 未命中任何规则才允许执行
 * </pre>
 *
 * <p>规则采用黑名单只是最后一道保护，不能代替 SSH 用户权限隔离、sudo 最小授权、
 * 容器隔离和操作审计。对于没有确认流程的当前版本，命中规则后直接拒绝执行。</p>
 */
@Component
public class DefaultCommandSafetyPolicy implements CommandSafetyPolicy {

    /**
     * 不可通过配置删除或覆盖的安全底线。
     *
     * <p>规则按声明顺序匹配，首次命中即返回，因此规则编号和顺序应保持稳定，
     * 便于日志检索与前端根据 ruleId 展示统一说明。</p>
     */
    private static final List<CommandRule> BUILT_IN_DENY_RULES = List.of(
            rule(
                    "EMPTY_COMMAND",
                    "命令不能为空",
                    "^\\s*$"
            ),
            rule(
                    "RECURSIVE_FORCE_DELETE",
                    "禁止递归强制删除根目录、用户目录或系统关键目录",
                    "\\brm\\s+(?=[^;&|\\n]*-[^\\s;&|\\n]*r)(?=[^;&|\\n]*-[^\\s;&|\\n]*f)"
                            + "[^;&|\\n]*\\s(?:--\\s+)?(?:/\\s*(?:$|[;&|])|/\\*|"
                            + "/(?:etc|usr|var|boot|dev|home|root)(?:/|/\\*|\\s|$)|"
                            + "~(?:/|/\\*|\\s|$)|\\$\\{?HOME\\}?(?:/|/\\*|\\s|$))"
            ),
            rule(
                    "FORMAT_FILESYSTEM",
                    "禁止格式化文件系统",
                    "\\b(?:mkfs(?:\\.[a-z0-9_+-]+)?|mke2fs)\\b"
            ),
            rule(
                    "WRITE_BLOCK_DEVICE",
                    "禁止直接覆盖磁盘或分区设备",
                    "\\bdd\\b[^;&|\\n]*\\bof\\s*=\\s*/dev/"
                            + "(?:sd[a-z][0-9]*|hd[a-z][0-9]*|vd[a-z][0-9]*|"
                            + "nvme[0-9]+n[0-9]+(?:p[0-9]+)?|mmcblk[0-9]+(?:p[0-9]+)?)\\b"
            ),
            rule(
                    "REDIRECT_TO_BLOCK_DEVICE",
                    "禁止通过重定向直接写入磁盘或分区设备",
                    "(?:>|\\btee\\b(?:\\s+-[a-z]+)*)\\s*/dev/"
                            + "(?:sd[a-z][0-9]*|hd[a-z][0-9]*|vd[a-z][0-9]*|"
                            + "nvme[0-9]+n[0-9]+(?:p[0-9]+)?|mmcblk[0-9]+(?:p[0-9]+)?)\\b"
            ),
            rule(
                    "WIPE_BLOCK_DEVICE",
                    "禁止擦除磁盘签名或粉碎块设备数据",
                    "(?:\\bwipefs\\b[^;&|\\n]*(?:--all|-a)\\b|"
                            + "\\bshred\\b[^;&|\\n]*/dev/(?:sd|hd|vd|nvme|mmcblk))"
            ),
            rule(
                    "PARTITION_TABLE_CHANGE",
                    "禁止通过自动化命令修改磁盘分区表",
                    "\\bparted\\b[^;&|\\n]*\\b(?:mklabel|mkpart|rm|resizepart)\\b"
            ),
            rule(
                    "RECURSIVE_PERMISSION_CHANGE",
                    "禁止递归修改根目录或系统关键目录的权限和归属",
                    "\\b(?:chmod|chown)\\s+(?=[^;&|\\n]*-[^\\s;&|\\n]*[Rr])"
                            + "[^;&|\\n]*\\s(?:/|/(?:etc|usr|var|boot|dev|home|root))(?:/|\\s|$)"
            ),
            rule(
                    "FORK_BOMB",
                    "禁止执行进程炸弹",
                    ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;?\\s*:"
            ),
            rule(
                    "REMOTE_SCRIPT_PIPE",
                    "禁止下载远程内容后直接交给 Shell 执行",
                    "\\b(?:curl|wget)\\b[^|\\n]*\\|\\s*(?:sudo\\s+)?(?:bash|sh|zsh|ksh)\\b"
            ),
            rule(
                    "ENCODED_SCRIPT_PIPE",
                    "禁止将解码后的内容直接交给 Shell 执行",
                    "\\b(?:base64\\s+(?:-d|--decode)|openssl\\s+base64\\s+-d)\\b"
                            + "[^|\\n]*\\|\\s*(?:sudo\\s+)?(?:bash|sh|zsh|ksh)\\b"
            ),
            rule(
                    "HOST_POWER_CONTROL",
                    "禁止通过 AI 工具关闭或重启主机",
                    "(?:^|[;&|]\\s*|\\bsudo\\s+)\\s*(?:shutdown|poweroff|halt|reboot)\\b"
            )
    );

    /** 允许进入正则匹配阶段的最大命令长度。 */
    private final int maxCommandLength;

    /** 最终规则集合：内置规则在前，配置追加规则在后。构造完成后不再修改。 */
    private final List<CommandRule> denyRules;

    /**
     * 创建策略并在启动阶段编译全部配置规则。
     *
     * <p>在构造阶段编译可以避免每次工具调用重复执行 Pattern.compile；如果配置
     * 正则非法则立即阻止应用启动，避免运行期间出现安全规则静默失效。</p>
     */
    public DefaultCommandSafetyPolicy(SshCommandSafetyProperties properties) {
        this.maxCommandLength = Math.max(1, properties.getMaxCommandLength());
        this.denyRules = new ArrayList<>(BUILT_IN_DENY_RULES);
        this.denyRules.addAll(compileAdditionalRules(properties.getAdditionalDenyRules()));
    }

    @Override
    public CommandSafetyDecision evaluate(String command) {
        // 所有匹配都基于规范化文本，降低全角字符、异常空白和反斜杠续行造成的绕过风险。
        String normalizedCommand = normalize(command);

        // 超长命令常用于拼接载荷或混淆；拒绝而不是截断，避免只检查前半段后执行整条命令。
        if (normalizedCommand.length() > maxCommandLength) {
            return CommandSafetyDecision.deny(
                    "COMMAND_TOO_LONG",
                    "命令长度超过安全限制 " + maxCommandLength + " 个字符",
                    normalizedCommand
            );
        }

        // 使用 find() 检查整条 Shell 命令中的任意片段，能够识别 &&、;、| 后的危险子命令。
        for (CommandRule rule : denyRules) {
            if (rule.pattern().matcher(normalizedCommand).find()) {
                return CommandSafetyDecision.deny(
                        rule.id(),
                        rule.description(),
                        normalizedCommand
                );
            }
        }

        // 只有长度合法且没有命中任何拒绝规则时才允许进入 SSH 层。
        return CommandSafetyDecision.allow(normalizedCommand);
    }

    /**
     * 编译 application.yml 中追加的拒绝规则。
     *
     * <p>配置规则只能追加，不能覆盖同名的内置规则；字段缺失或正则非法时采用
     * fail-fast 策略抛出异常，确保应用不会带着残缺安全策略继续运行。</p>
     */
    private List<CommandRule> compileAdditionalRules(
            List<SshCommandSafetyProperties.DenyRule> configuredRules) {
        if (configuredRules == null || configuredRules.isEmpty()) {
            return List.of();
        }

        List<CommandRule> compiledRules = new ArrayList<>(configuredRules.size());
        for (SshCommandSafetyProperties.DenyRule configuredRule : configuredRules) {
            if (configuredRule == null
                    || isBlank(configuredRule.getId())
                    || isBlank(configuredRule.getDescription())
                    || isBlank(configuredRule.getPattern())) {
                throw new IllegalStateException(
                        "ai.ssh.command-safety.additional-deny-rules 必须配置 id、description 和 pattern"
                );
            }

            int flags = Pattern.UNICODE_CASE;
            if (configuredRule.isCaseInsensitive()) {
                flags |= Pattern.CASE_INSENSITIVE;
            }

            try {
                compiledRules.add(new CommandRule(
                        configuredRule.getId(),
                        configuredRule.getDescription(),
                        Pattern.compile(configuredRule.getPattern(), flags)
                ));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException(
                        "无效的 SSH 命令安全正则，ruleId=" + configuredRule.getId(),
                        e
                );
            }
        }
        return compiledRules;
    }

    /** 创建忽略大小写且支持 Unicode 大小写折叠的内置规则。 */
    private static CommandRule rule(String id, String description, String regex) {
        return new CommandRule(
                id,
                description,
                Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        );
    }

    /**
     * 生成仅用于策略匹配的规范化命令。
     *
     * <ul>
     *     <li>NFKC：将全角字符等兼容字符统一为常规形式。</li>
     *     <li>移除 NUL：防止日志、解析器与 Shell 对字符串终点理解不一致。</li>
     *     <li>移除反斜杠换行：还原 Shell 的续行语义。</li>
     *     <li>合并空白：让制表符、换行和 Unicode 空格使用相同规则匹配。</li>
     * </ul>
     *
     * <p>规范化结果不会替换原始命令执行，避免安全检查过程改变合法命令语义。</p>
     */
    private String normalize(String command) {
        if (command == null) {
            return "";
        }

        String normalized = Normalizer.normalize(command, Normalizer.Form.NFKC);
        normalized = normalized.replace("\0", "");
        normalized = normalized.replaceAll("\\\\\r?\n", "");
        normalized = normalized.replaceAll("[\\p{Z}\\s]+", " ");
        return normalized.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 编译后的内部规则，避免在每次 executeCommand 调用时重复编译正则。 */
    private record CommandRule(String id, String description, Pattern pattern) {
    }
}
