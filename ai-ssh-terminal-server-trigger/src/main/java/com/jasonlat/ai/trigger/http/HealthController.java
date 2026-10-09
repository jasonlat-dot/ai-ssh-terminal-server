package com.jasonlat.ai.trigger.http;

import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class HealthController {

    /**
     * 返回当前后端进程的基础健康状态。
     *
     * <p>该检查只确认 Spring Web 服务已经可以处理 HTTP 请求，不访问数据库、
     * SSH 连接、模型服务或其他外部依赖，因此可以被客户端高频轮询。</p>
     *
     * @return HTTP 200 以及包含服务状态和检查时间的 JSON 响应
     */
    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        // 能执行到这里就说明当前 Web 容器已经完成启动并可以正常响应请求。
        HealthResponse response = new HealthResponse("UP", Instant.now());
        log.info("监控检查：{}", response);
        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查响应。
     *
     * @param status    服务状态；基础存活检查固定返回 UP
     * @param checkedAt 服务端完成本次检查的 UTC 时间
     */
    public record HealthResponse(String status, Instant checkedAt) {
        @Override
        public @NonNull String toString() {
            return "HealthResponse{status='" + status + "', checkedAt=" + checkedAt + "}";
        }
    }

}
