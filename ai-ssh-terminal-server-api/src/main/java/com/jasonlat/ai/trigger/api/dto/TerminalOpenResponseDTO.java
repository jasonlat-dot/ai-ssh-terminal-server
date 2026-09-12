package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalOpenResponseDTO {

    /** 终端会话ID */
    private String sessionId;

    /** SSH连接ID */
    private String connectionId;

    /** 初始终端输出（连接后的欢迎信息） */
    private String initialOutput;

}
