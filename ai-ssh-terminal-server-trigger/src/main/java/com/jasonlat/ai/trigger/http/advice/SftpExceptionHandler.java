package com.jasonlat.ai.trigger.http.advice;

import com.jasonlat.ai.domain.sftp.model.SftpException;
import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.trigger.http.sftp.SftpController;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import lombok.extern.slf4j.Slf4j;

/** 二进制响应已提交后不追加 JSON；客户端结合流长度和任务状态识别失败。 */
@Slf4j
@RestControllerAdvice(assignableTypes = SftpController.class)
public class SftpExceptionHandler {

    /**
     * 统一处理 SFTP Controller 抛出的异常。
     *
     * <p>普通 JSON 请求返回业务错误结构；下载响应若已开始写二进制流，则只记录日志并结束，
     * 不能再追加 JSON，否则客户端下载文件会被错误正文污染。</p>
     */
    @ExceptionHandler(Exception.class)
    public Response<Void> handle(
            Exception exception,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SftpException failure = translate(exception);
        String userId = request.getUserPrincipal() == null
                ? null
                : request.getUserPrincipal().getName();
        log.warn(
                "SFTP 请求失败 userId={} method={} uri={} query={} code={} errorType={} errorMessage={}",
                userId,
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                failure.getCode(),
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                exception.getCause()
        );

        // 下载响应一旦开始写入二进制内容，就不能再混入 JSON 错误正文。
        if (response.isCommitted()) {
            return null;
        }

        response.reset();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(httpStatus(failure.getCode()));
        return Response.build(failure.getCode(), failure.getMessage(), null);
    }

    /** 将框架解析异常与未知异常收敛成前端可识别的 SFTP 错误。 */
    private static SftpException translate(Exception exception) {
        if (exception instanceof SftpException sftpException) {
            return sftpException;
        }
        if (exception instanceof HttpMessageNotReadableException
                || exception instanceof IllegalArgumentException) {
            return new SftpException("SFTP_INVALID", "请求参数不合法");
        }
        return new SftpException("SFTP_IO_ERROR", "SFTP 请求失败", exception);
    }

    /** 业务错误码到 HTTP 状态码的唯一映射入口。 */
    private static int httpStatus(String code) {
        return switch (code) {
            case "SFTP_UNAUTHORIZED" -> 401;
            case "SFTP_FORBIDDEN", "SFTP_PATH_FORBIDDEN" -> 403;
            case "SFTP_NOT_FOUND" -> 404;
            case "SFTP_BUSY" -> 429;
            case "SFTP_CONFLICT", "SFTP_CANCELLED", "SFTP_SOURCE_CHANGED" -> 409;
            case "SFTP_LIMIT" -> 413;
            case "SFTP_INVALID", "SFTP_SIZE_MISMATCH" -> 400;
            default -> 502;
        };
    }
}
