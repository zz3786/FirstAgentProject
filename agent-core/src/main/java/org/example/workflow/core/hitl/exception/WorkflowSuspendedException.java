package org.example.workflow.core.hitl.exception;

/**
 * 工作流挂起异常——用于中断当前执行流
 * <p>
 * <b>这不是"错误"</b>——是"控制流"信号。
 * 抛出后由上层捕获，返回 SUSPENDED 状态给调用方。
 */
public class WorkflowSuspendedException extends RuntimeException {

    private final String taskId;

    public WorkflowSuspendedException(String taskId, String message) {
        super(message);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}