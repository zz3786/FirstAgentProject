package org.example.core.workflow.core.dsl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单节点定义
 * <p>
 * <b>字段按 type 生效</b>：
 * <ul>
 *   <li>type=node：用 ref</li>
 *   <li>type=switch：用 on + cases + defaultBranch</li>
 *   <li>type=parallel：用 tasks</li>
 *   <li>type=hitl：用 when + assignee + timeoutMinutes + fallback + prompt</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeDefinition {

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private String type;      // node / switch / parallel / hitl / end

    @JsonProperty("name")
    private String name;      // 可选——展示用

    // ========== type=node ==========
    @JsonProperty("ref")
    private String ref;       // 引用的 WorkflowNode bean 名

    // ========== type=switch ==========
    @JsonProperty("on")
    private String on;        // 表达式，如 "${branchTaken}"

    @JsonProperty("cases")
    private Map<String, String> cases;   // 值 → 目标节点 id

    @JsonProperty("default")
    private String defaultBranch;        // 默认目标节点 id

    // ========== type=parallel ==========
    @JsonProperty("tasks")
    private List<ParallelTaskDef> tasks;

    // ========== type=hitl ==========
    @JsonProperty("when")
    private String when;                 // 表达式——false 时跳过 HITL

    @JsonProperty("assignee")
    private String assignee;

    @JsonProperty("timeout-minutes")
    private Long timeoutMinutes;

    @JsonProperty("fallback")
    private String fallback;             // REJECT / AUTO_APPROVE

    @JsonProperty("prompt")
    private String prompt;               // 给人工看的提示（支持 ${} 表达式）

    // ========== 通用 ==========
    @JsonProperty("timeout-ms")
    private Long timeoutMs;

    @JsonProperty("retry")
    private RetryConfig retry;

    /** 并行任务定义 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParallelTaskDef {
        @JsonProperty("name")
        private String name;

        @JsonProperty("ref")
        private String ref;              // 引用的 DataFetcher bean 名

        @JsonProperty("critical")
        private boolean critical = false;

        @JsonProperty("fallback")
        private Object fallback;

        @JsonProperty("timeout-ms")
        private Long timeoutMs;
    }
}