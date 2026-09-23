package org.example.api.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * @RestControllerAdvice 的作用范围是整个 Spring MVC 的 DispatcherServlet——它拦截的是"冒泡到 Controller 层的所有异常"，不管异常最初来自哪个模块。
 *
 * 请求 → Controller（agent-api）
 *          ↓ 调用
 *        ChatService（agent-core）
 *          ↓ 调用
 *        LongTermMemoryService（agent-memory）
 *          ↓
 *        Redis 挂了 → 抛 RedisConnectionFailureException
 *          ↓ 冒泡回 Controller
 *        DispatcherServlet
 *          ↓
 *        GlobalExceptionHandler 捕获 ✅
 *
 *        只要异常最终从 Controller 方法抛出，就能被拦截——无论来自哪个模块。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** AI 服务异常（DeepSeek 400/401/429 等） */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClient(WebClientResponseException e) {
        log.error("AI 服务调用失败，状态码: {}，响应: {}",
                e.getStatusCode(), e.getResponseBodyAsString());

        String msg;
        int status = e.getStatusCode().value();
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

    /** Redis 连接异常 */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedis(RedisConnectionFailureException e) {
        log.error("Redis 连接失败", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(503, "记忆服务暂时不可用，请稍后重试"));
    }

    /** 未认证 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("业务状态异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(401, e.getMessage() == null ? "请先登录" : e.getMessage()));
    }

    /** 参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数不合法");
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, msg));
    }

    /** 缺少请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "缺少必要参数：" + e.getParameterName()));
    }

    /** 兜底：任何未捕获的异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务出了点问题，请稍后重试"));
    }
}