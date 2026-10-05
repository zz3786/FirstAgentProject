package org.example.core.plan.model;

import java.util.List;

/**
 * 最终返回
 */
public record PlanResult(
        String executionId,
        boolean success,
        PlanStatus status,
        String finalAnswer,
        Plan plan,
        List<StepResult> stepResults,
        int replanCount,
        long totalCostMs,
        String error
) {}