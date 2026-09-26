package com.jasonlat.ai.trigger.http.advice;

import com.jasonlat.ai.trigger.api.response.Response;
import com.jasonlat.ai.trigger.http.FileController;
import com.jasonlat.ai.types.enums.ResponseCode;
import com.jasonlat.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestControllerAdvice(assignableTypes = FileController.class)
public class FileExceptionHandler {
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Response<Void>> handleBusiness(AppException e) {
        HttpStatus status = switch (e.getCode()) {
            case "FILE_STORAGE_NOT_CONFIGURED", "FILE_STORAGE_CONFIG_INVALID",
                 "FILE_STORAGE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "FILE_UPLOAD_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
            case "FILE_INVALID", "FILE_TYPE_NOT_ALLOWED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("文件上传请求失败 code={}", e.getCode());
        return ResponseEntity.status(status).body(Response.build(e.getCode(), e.getInfo(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Response<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, ResponseCode.FILE_TOO_LARGE);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Response<Void>> handleInvalidMultipart(Exception e) {
        return error(HttpStatus.BAD_REQUEST, ResponseCode.FILE_INVALID);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleUnexpected(Exception e) {
        log.error("文件上传异常 errorType={}", e.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ResponseCode.FILE_UPLOAD_FAILED);
    }

    private ResponseEntity<Response<Void>> error(HttpStatus status, ResponseCode code) {
        return ResponseEntity.status(status).body(Response.build(code.getCode(), code.getInfo(), null));
    }
}
