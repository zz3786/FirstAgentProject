package org.example.core.workflow.core.hitl.exception;

/**
 * HITL 异常
 */
public class HitlException extends RuntimeException {

    public enum Code {
        TASK_NOT_FOUND,       // 任务不存在
        TASK_ALREADY_DECIDED, // 任务已经决策过
        TASK_TIMEOUT,         // 任务已超时
        INVALID_DECISION,     // 非法决策
        STORE_ERROR,          // 存储失败
        RESUME_FAILED         // 恢复执行失败
    }

    private final Code code;
    private final String taskId;

    public HitlException(Code code, String taskId, String message) {
        super(message);
        this.code = code;
        this.taskId = taskId;
    }

    public HitlException(Code code, String taskId, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.taskId = taskId;
    }

    public Code getCode() {
        return code;
    }

    public String getTaskId() {
        return taskId;
    }
}