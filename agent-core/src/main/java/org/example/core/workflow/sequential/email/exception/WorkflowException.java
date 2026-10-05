package org.example.core.workflow.sequential.email.exception;

/**
 * 工作流异常——Step 内部失败抛这个，主流程统一捕获
 */
public class WorkflowException extends RuntimeException {

    private final String stepName;

    public WorkflowException(String stepName, String message, Throwable cause) {
        super("[步骤 " + stepName + "] " + message, cause);
        this.stepName = stepName;
    }

    public String getStepName() {
        return stepName;
    }
}