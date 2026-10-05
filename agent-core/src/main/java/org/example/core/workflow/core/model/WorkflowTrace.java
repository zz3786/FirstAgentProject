package org.example.core.workflow.core.model;

import java.time.LocalDateTime;

/**
 * 单步执行轨迹——可观测性的核心载体
 * <p>
 * 结构化输出，便于 ELK/Loki 提取。
 */
public record WorkflowTrace(
        String nodeName,
        String status,          // SUCCESS / FAILED / SKIPPED
        String branchTaken,     // 如果该节点是决策点，记录走了哪条分支
        long costMs,
        String detail,
        LocalDateTime at
) {
    public static WorkflowTrace success(String nodeName, long costMs, String detail) {
        return new WorkflowTrace(nodeName, "SUCCESS", null, costMs, detail, LocalDateTime.now());
    }

    public static WorkflowTrace successWithBranch(String nodeName, String branch, long costMs, String detail) {
        return new WorkflowTrace(nodeName, "SUCCESS", branch, costMs, detail, LocalDateTime.now());
    }

    public static WorkflowTrace failed(String nodeName, long costMs, String error) {
        return new WorkflowTrace(nodeName, "FAILED", null, costMs, error, LocalDateTime.now());
    }

    public static WorkflowTrace skipped(String nodeName, String reason) {
        return new WorkflowTrace(nodeName, "SKIPPED", null, 0, reason, LocalDateTime.now());
    }
}