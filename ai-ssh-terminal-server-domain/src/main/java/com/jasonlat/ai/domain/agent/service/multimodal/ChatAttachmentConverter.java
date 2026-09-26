package com.jasonlat.ai.domain.agent.service.multimodal;

import com.google.genai.types.Part;

/** 附件转换策略；新增可识别格式时注册新实现，不在调用节点中增加类型分支。 */
public interface ChatAttachmentConverter {
    /** 是否处理该扩展名，扩展名已转小写且不含点。 */
    boolean supports(String extension);

    /** 模型接收的媒体 MIME；文本策略返回 null，表示转换为普通文字。 */
    String mediaType(String extension);

    /** 校验实际内容并生成 ADK Part，maxTextChars 是本次请求尚可使用的文本额度。 */
    Part convert(String extension, byte[] bytes, int maxTextChars);
}
