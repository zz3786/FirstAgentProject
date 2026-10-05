package org.example.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单步执行结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepResult(
        int stepId,
        String description,
        String tool,
        String status,       // SUCCESS / FAILED / SKIPPED
        String output,       // 成功时的产出
        String error,        // 失败原因
        long costMs,
        int retryCount
) {
    public static StepResult success(PlanStep step, String output, long costMs, int retries) {
        return new StepResult(step.id(), step.description(), step.tool(),
                "SUCCESS", output, null, costMs, retries);
    }

    public static StepResult failed(PlanStep step, String error, long costMs, int retries) {
        return new StepResult(step.id(), step.description(), step.tool(),
                "FAILED", null, error, costMs, retries);
    }
}