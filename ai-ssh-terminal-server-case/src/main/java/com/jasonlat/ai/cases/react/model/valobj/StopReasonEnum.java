package com.jasonlat.ai.cases.react.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 会话中断原因枚举
 */
@Getter
@AllArgsConstructor
public enum StopReasonEnum {
    /** 用户主动停止 */
    USER_STOP("user_stop", "用户主动停止"),
    /** 空闲超时 */
    IDLE_TIMEOUT("idle_timeout", "空闲超时"),
    /** 达到最大执行步数 */
    MAX_STEPS("max_steps", "达到最大执行步数"),
    /** 达到最大工具调用次数 */
    MAX_TOOL_CALLS("max_tool_calls", "达到最大工具调用次数"),
    /** 返回 finish 指令，终止循环 */
    FINISH("finish", "返回 finish 指令，终止循环"),
    /** 无工具调用且无终止指令 → 循环完成 */
    COMPLETED("completed", "无工具调用且无终止指令 → 循环完成"),
    /** 错误 */
    ERROR("error", "未知错误"),
    ;

    /** 编码值，与数据库/JSON字符串一致 */
    private final String code;
    /** 描述信息 */
    private final String desc;

    /**
     * 根据code字符串获取枚举
     * @param code 编码
     * @return 对应枚举，找不到返回null
     */
    public static StopReasonEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (StopReasonEnum reason : StopReasonEnum.values()) {
            if (reason.getCode().equals(code)) {
                return reason;
            }
        }
        return null;
    }
}