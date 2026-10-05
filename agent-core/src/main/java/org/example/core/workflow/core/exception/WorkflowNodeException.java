package org.example.core.workflow.core.exception;

/**
 * 节点执行异常
 */
public class WorkflowNodeException extends RuntimeException {

    private final String nodeName;

    public WorkflowNodeException(String nodeName, String message) {
        this(nodeName, message, null);
    }

    public WorkflowNodeException(String nodeName, String message, Throwable cause) {
        super("[节点 " + nodeName + "] " + message, cause);
        this.nodeName = nodeName;
    }

    public String getNodeName() {
        return nodeName;
    }
}