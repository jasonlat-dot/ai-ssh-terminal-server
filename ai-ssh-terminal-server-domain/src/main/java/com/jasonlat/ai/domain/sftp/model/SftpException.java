package com.jasonlat.ai.domain.sftp.model;

/** SFTP 业务错误；HTTP 状态映射留在入口层。 */
public class SftpException extends RuntimeException {
    private final String code;
    public SftpException(String code, String message) { super(message); this.code = code; }
    public SftpException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String getCode() { return code; }
}
