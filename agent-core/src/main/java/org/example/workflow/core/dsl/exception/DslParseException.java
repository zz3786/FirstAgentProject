package org.example.workflow.core.dsl.exception;

/**
 * DSL 解析失败
 */
public class DslParseException extends RuntimeException {
    public DslParseException(String message) { super(message); }
    public DslParseException(String message, Throwable cause) { super(message, cause); }
}