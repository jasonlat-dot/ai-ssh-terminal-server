package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalExecRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 命令内容 */
    private String command;

}
