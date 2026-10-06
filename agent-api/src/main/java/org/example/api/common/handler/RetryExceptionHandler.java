package org.example.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.retry.RetryExhaustedException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@Order(14)
public class RetryExceptionHandler {

    @ExceptionHandler(RetryExhaustedException.class)
    public ResponseEntity<ApiResponse<Void>> handle(RetryExhaustedException e) {
        log.warn("重试耗尽: name={}, attempts={}, lastError={}",
                e.getStats().name(), e.getStats().totalAttempts(),
                e.getStats().finalError());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(503, "服务暂时不稳定，请稍后重试"));
    }
}