package org.example.core.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 计划中的单个步骤
 * <p>
 * 字段说明通过 {@code @JsonPropertyDescription} 传给 LLM——让模型知道每个字段该填什么。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStep(
        @JsonPropertyDescription("步骤序号，从 1 开始递增")
        int id,

        @JsonPropertyDescription("步骤的完整描述，一句话说清楚要做什么")
        String description,

        @JsonPropertyDescription("执行该步骤使用的工具名；纯 LLM 推理填 null")
        String tool,

        @JsonPropertyDescription("依赖的前置步骤 id 列表；无依赖填空数组")
        List<Integer> dependsOn,

        @JsonPropertyDescription("预期产出，一句话")
        String expectedOutput
) {}