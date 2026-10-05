package org.example.core.plan.exception;

/**
 * Plan-and-Execute 统一异常
 * <p>
 * 用 code 区分错误类型，方便上层做不同处理。
 */
public class PlanException extends RuntimeException {

    public enum Code {
        PLANNING_FAILED,      // 规划阶段失败（LLM 输出无法解析）
        VALIDATION_FAILED,    // 计划校验失败（工具名非法、步数超限等）
        EXECUTION_FAILED,     // 执行阶段失败（某步重试后仍失败）
        TIMEOUT,              // 整体超时
        REPLAN_EXHAUSTED,     // 重规划次数用尽
        TOOL_NOT_ALLOWED,     // 工具不在白名单
        STATE_CONFLICT        // 并发冲突（同一 executionId 被重复执行）
    }

    private final Code code;
    private final String stepName;

    public PlanException(Code code, String message) {
        this(code, message, null, null);
    }

    public PlanException(Code code, String message, String stepName, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.stepName = stepName;
    }

    public Code getCode() {
        return code;
    }

    public String getStepName() {
        return stepName;
    }
}