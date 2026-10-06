package org.example.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.plan.exception.PlanException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Plan-and-Execute 异常处理器
 */
@Slf4j
@RestControllerAdvice
@Order(11)
public class PlanExceptionHandler {

    @ExceptionHandler(PlanException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlan(PlanException e) {
        log.warn("Plan 异常: code={}, step={}, msg={}",
                e.getCode(), e.getStepName(), e.getMessage());

        HttpStatus status;
        int code;
        String msg;

        switch (e.getCode()) {
            case PLANNING_FAILED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;   // 422
                code = 422;
                msg = "任务规划失败，请重新描述需求";
            }
            case VALIDATION_FAILED -> {
                status = HttpStatus.BAD_REQUEST;
                code = 400;
                msg = "计划校验失败：" + e.getMessage();
            }
            case EXECUTION_FAILED -> {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                code = 500;
                msg = "计划执行失败：" + e.getMessage();
            }
            case TIMEOUT -> {
                status = HttpStatus.GATEWAY_TIMEOUT;
                code = 504;
                msg = "任务执行超时，请稍后重试";
            }
            case REPLAN_EXHAUSTED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = 422;
                msg = "重规划次数用尽，任务未能完成";
            }
            case TOOL_NOT_ALLOWED -> {
                status = HttpStatus.FORBIDDEN;
                code = 403;
                msg = "使用了未授权的工具";
            }
            case STATE_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = 409;
                msg = e.getMessage() == null ? "执行冲突，请稍后重试" : e.getMessage();
            }
            default -> {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                code = 500;
                msg = "任务执行失败，请稍后重试";
            }
        }

        return ResponseEntity.status(status).body(ApiResponse.fail(code, msg));
    }
}