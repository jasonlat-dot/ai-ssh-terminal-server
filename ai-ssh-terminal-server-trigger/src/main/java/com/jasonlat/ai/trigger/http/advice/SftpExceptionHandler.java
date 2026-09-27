package com.jasonlat.ai.trigger.http.advice;

import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.trigger.http.sftp.SftpController;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import lombok.extern.slf4j.Slf4j;

/** 二进制响应已提交后不追加 JSON；客户端结合流长度和任务状态识别失败。 */
@Slf4j
@RestControllerAdvice(assignableTypes = SftpController.class)
public class SftpExceptionHandler {
    @ExceptionHandler(Exception.class)
    public Response<Void> handle(Exception exception, HttpServletResponse response) {
        SftpException failure = exception instanceof SftpException e ? e
                : exception instanceof HttpMessageNotReadableException || exception instanceof IllegalArgumentException
                ? new SftpException("SFTP_INVALID", "请求参数不合法")
                : new SftpException("SFTP_IO_ERROR", "SFTP 请求失败");
        log.warn("SFTP 请求失败 code={} errorType={}", failure.getCode(), exception.getClass().getSimpleName());
        if (response.isCommitted()) return null;
        response.reset();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(switch (failure.getCode()) {
            case "SFTP_UNAUTHORIZED" -> 401;
            case "SFTP_FORBIDDEN", "SFTP_PATH_FORBIDDEN" -> 403;
            case "SFTP_NOT_FOUND" -> 404;
            case "SFTP_BUSY" -> 429;
            case "SFTP_CONFLICT", "SFTP_CANCELLED", "SFTP_SOURCE_CHANGED" -> 409;
            case "SFTP_LIMIT" -> 413;
            case "SFTP_INVALID", "SFTP_SIZE_MISMATCH" -> 400;
            default -> 502;
        });
        return Response.build(failure.getCode(), failure.getMessage(), null);
    }
}
