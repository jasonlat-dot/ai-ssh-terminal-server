package com.jasonlat.ai.domain.agent.model.valobj;

/**
 * 对话附件限制，独立于上传限制；正文只在本次模型调用期间使用。
 * @param maxFiles 单次请求最多附件数
 * @param maxTotalBytes 单次请求原始附件总字节数
 * @param maxTextChars 单次请求文本附件正文总字符数
 * @param maxConcurrentRequests 单个后端同时执行的附件对话数
 * @param allowAnonymousFiles 是否兼容 ownerId 为空的匿名上传文件
 */
public record ChatAttachmentPolicy(
        int maxFiles,
        long maxTotalBytes,
        int maxTextChars,
        int maxConcurrentRequests,
        boolean allowAnonymousFiles) {
    public ChatAttachmentPolicy {
        if (maxFiles < 1 || maxTotalBytes < 1 || maxTotalBytes >= Integer.MAX_VALUE
                || maxTextChars < 1 || maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("对话附件限制必须为正数，总字节数必须小于 Integer.MAX_VALUE");
        }
    }
}
