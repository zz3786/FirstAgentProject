package org.example.core.workflow.core.model;

/**
 * 单个节点的执行结果
 * <p>
 * 用 record 表达"不可变快照"——节点执行完就冻结，不做二次修改。
 */
public record NodeResult(
        String nodeName,
        boolean success,
        String output,
        String error,
        long costMs
) {
    public static NodeResult success(String nodeName, String output, long costMs) {
        return new NodeResult(nodeName, true, output, null, costMs);
    }

    public static NodeResult failed(String nodeName, String error, long costMs) {
        return new NodeResult(nodeName, false, null, error, costMs);
    }
}