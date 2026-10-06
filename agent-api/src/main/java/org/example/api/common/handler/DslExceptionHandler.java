package org.example.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.workflow.core.dsl.exception.DslParseException;
import org.example.core.workflow.core.dsl.exception.DslValidationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * DSL 异常处理器
 */
@Slf4j
@RestControllerAdvice
@Order(12)
public class DslExceptionHandler {

    /**
     * DSL 解析失败——通常是上传的 YAML 格式错误
     */
    @ExceptionHandler(DslParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleParse(DslParseException e) {
        log.warn("DSL 解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "工作流定义解析失败：" + e.getMessage()));
    }

    /**
     * DSL 校验失败——携带所有错误
     */
    @ExceptionHandler(DslValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(DslValidationException e) {
        log.warn("DSL 校验失败，共 {} 条错误", e.getErrors().size());
        Map<String, Object> data = Map.of("errors", e.getErrors());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "工作流定义校验失败", data));
    }
}