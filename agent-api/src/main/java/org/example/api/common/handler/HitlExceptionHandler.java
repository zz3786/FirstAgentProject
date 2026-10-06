package org.example.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.workflow.core.hitl.exception.HitlException;
import org.example.core.workflow.core.hitl.exception.WorkflowSuspendedException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * HITL 异常处理器
 *
 * <h3>处理两类异常</h3>
 * <ul>
 *   <li>{@link HitlException} —— 按 Code 映射到不同 HTTP 状态</li>
 *   <li>{@link WorkflowSuspendedException} —— 工作流挂起——不是错误，
 *       返回 202 Accepted 表示"已接受，等待人工处理"</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@Order(10)
public class HitlExceptionHandler {

    // ==================== HitlException ====================

    @ExceptionHandler(HitlException.class)
    public ResponseEntity<ApiResponse<Void>> handleHitl(HitlException e) {
        log.warn("HITL 异常: code={}, taskId={}, msg={}",
                e.getCode(), e.getTaskId(), e.getMessage());

        HttpStatus status;
        int code;
        String msg;

        switch (e.getCode()) {
            case TASK_NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = 404;
                msg = "任务不存在或已过期";
            }
            case TASK_ALREADY_DECIDED -> {
                status = HttpStatus.CONFLICT;
                code = 409;
                msg = "该任务已被处理过，请勿重复操作";
            }
            case TASK_TIMEOUT -> {
                status = HttpStatus.GONE;
                code = 410;
                msg = "该任务已超时，无法再处理";
            }
            case INVALID_DECISION -> {
                status = HttpStatus.BAD_REQUEST;
                code = 400;
                msg = e.getMessage() == null ? "非法的决策请求" : e.getMessage();
            }
            default -> {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                code = 500;
                msg = "任务处理失败，请稍后重试";
            }
        }

        return ResponseEntity.status(status).body(ApiResponse.fail(code, msg));
    }

    // ==================== WorkflowSuspendedException ====================

    /**
     * 工作流挂起——不是错误
     * <p>
     * 返回 202 Accepted —— 语义是"请求已接受，等待异步处理完成"。
     */
    @ExceptionHandler(WorkflowSuspendedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleSuspended(WorkflowSuspendedException e) {
        log.info("工作流挂起: taskId={}, msg={}", e.getTaskId(), e.getMessage());

        Map<String, Object> data = Map.of(
                "status", "SUSPENDED",
                "taskId", e.getTaskId(),
                "message", "工作流已挂起，等待人工处理"
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(data));
    }
}