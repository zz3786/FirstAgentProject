package org.example.api.common;

import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.UnauthorizedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 全局异常处理器——只处理"通用"异常
 *
 * <h3>职责边界</h3>
 * 只处理这 5 类：
 * <ol>
 *   <li>参数校验失败（@Valid）</li>
 *   <li>缺少请求参数</li>
 *   <li>AI 服务调用异常</li>
 *   <li>Redis 连接异常</li>
 *   <li>兜底（未捕获的异常）</li>
 * </ol>
 *
 * <p><b>各模块的业务异常</b>（Hitl / Plan / Dsl / Workflow）由各自的
 * {@code *ExceptionHandler} 处理——见 {@code org.example.api.common.handler} 包。
 *
 * <p><b>为什么拆</b>：
 * 原来的做法是"一个类处理所有异常"——每加一个模块就加一个 @ExceptionHandler，
 * 永不收敛。拆开后"谁定义异常谁定义 handler"，符合开闭原则。
 *
 * <p><b>执行优先级</b>：{@code @Order(LOWEST_PRECEDENCE)} —— 放最后，
 * 只有其他 handler 都不处理时才走兜底。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    // ==================== 参数校验 ====================

    /**
     * @Valid 校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数不合法");
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, msg));
    }

    /**
     * 缺少必填请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "缺少必要参数：" + e.getParameterName()));
    }

    // ==================== 基础设施 ====================

    /**
     * AI 服务调用异常（DeepSeek 400/401/429 等）
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClient(WebClientResponseException e) {
        log.error("AI 服务调用失败，状态码: {}，响应: {}",
                e.getStatusCode(), e.getResponseBodyAsString());

        int status = e.getStatusCode().value();
        String msg;
        if (status == 401) {
            msg = "AI 服务认证失败，请联系管理员检查 API Key";
        } else if (status == 429) {
            msg = "AI 服务请求太频繁，请稍后重试";
        } else if (status >= 500) {
            msg = "AI 服务暂时不可用，请稍后重试";
        } else {
            msg = "AI 服务拒绝了本次请求，请稍后重试或换个问法";
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(503, msg));
    }

    /**
     * Redis 连接异常
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedis(RedisConnectionFailureException e) {
        log.error("Redis 连接失败", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(503, "记忆服务暂时不可用，请稍后重试"));
    }

    // ==================== 兜底 ====================

    /**
     * 兜底——任何未被其他 handler 处理的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务出了点问题，请稍后重试"));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(401, e.getMessage() == null ? "请先登录" : e.getMessage()));
    }
}