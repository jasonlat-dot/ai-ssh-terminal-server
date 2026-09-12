package com.jasonlat.ai.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalResizeRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 新的列数 */
    private Integer cols;

    /** 新的行数 */
    private Integer rows;

}
