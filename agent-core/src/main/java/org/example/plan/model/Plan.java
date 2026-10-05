package org.example.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 完整任务计划
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plan(
        @JsonPropertyDescription("对用户原始需求的简要重述，用于校验理解是否正确")
        String goal,

        @JsonPropertyDescription("有序的步骤列表")
        List<PlanStep> steps
) {}