package org.example.workflow.core.dsl.exception;

import java.util.List;

/**
 * DSL 校验失败——携带所有错误
 */
public class DslValidationException extends RuntimeException {

    private final List<String> errors;

    public DslValidationException(String workflowId, List<String> errors) {
        super("工作流 [" + workflowId + "] 校验失败:\n  - " + String.join("\n  - ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() { return errors; }
}