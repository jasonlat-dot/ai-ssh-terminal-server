package com.jasonlat.ai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 对话附件配置在 app 绑定，再转换成领域层不可变策略。 */
@Data
@ConfigurationProperties(prefix = "ai.chat.attachments")
public class ChatAttachmentProperties {
    /** 每次请求最多引用的文件数量。 */
    private int maxFiles = 4;
    /** 本次请求所有附件的原始字节数之和，不包含 Base64 编码开销。 */
    private DataSize maxTotalSize = DataSize.ofMegabytes(20);
    /** 文本附件正文的合计字符上限，超限拒绝，不静默截断。 */
    private int maxTextChars = 60000;
    /** 单个后端同时执行的附件对话数，许可持续到模型调用结束。 */
    private int maxConcurrentRequests = 2;
    /** 兼容当前匿名上传；关闭后 ownerId 为空的历史文件不能用于聊天。 */
    private boolean allowAnonymousFiles = true;
}
