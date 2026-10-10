package org.example.multiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 子任务（D72）
 *
 * <h3>为什么 D72 的 SubTask 不带 dependsOn</h3>
 * <p>
 * "子任务有依赖"是 <b>Plan-and-Execute</b> 的领域——
 * 那里有 {@code PlanStep.dependsOn}、有拓扑调度、有重规划。
 * 强行让 Supervisor 也支持依赖，会和 Plan 的语义打架，代码翻倍但收益有限。
 *
 * <p>
 * Supervisor 的定位是"<b>多个独立视角并行回答同一问题</b>"：
 * <ul>
 *   <li>财务 Agent 回答"这单能不能退"</li>
 *   <li>法务 Agent 回答"退款条款是否合规"</li>
 *   <li>技术 Agent 回答"退款系统怎么改"</li>
 * </ul>
 * 三者相互独立，不需要串行依赖。
 *
 * <p>
 * <b>如果将来真需要依赖</b>：不要给 SubTask 加 dependsOn，
 * 而是让上层（Facade）判断——"需要依赖的问题请走 /agent/plan/execute"。
 * 保持两个模式的边界清晰。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code id}——子任务序号，从 1 开始。用于结果排序、日志追踪</li>
 *   <li>{@code description}——给 Worker 的完整任务描述。LLM 生成</li>
 *   <li>{@code role}——目标 Worker 角色。TaskDispatcher 按它路由</li>
 *   <li>{@code expectedOutput}——期望产出的一句话描述。用于聚合时参考</li>
 * </ul>
 *
 * <h3>为什么用 @JsonPropertyDescription</h3>
 * <p>
 * {@code BeanOutputConverter<TaskGraph>} 会用这些描述生成 JSON schema，
 * 告诉 LLM 每个字段该填什么。<b>描述质量直接影响拆解质量</b>——
 * 认真写。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubTask(
        @JsonPropertyDescription("子任务序号，从 1 开始递增。用于排序和引用")
        int id,

        @JsonPropertyDescription("子任务的完整描述，一句话说清要做什么。" +
                "必须具体到 Worker 能独立完成，不需要和其他子任务协调")
        String description,

        @JsonPropertyDescription("执行该子任务的 Worker 角色。" +
                "可选值：FINANCIAL（财务）、TECHNICAL（技术）、LEGAL（法务）。" +
                "不确定时选最接近的")
        AgentRole role,

        @JsonPropertyDescription("期望产出的一句话描述，用于后续聚合参考")
        String expectedOutput
) {

    /**
     * 紧凑构造器——基础校验
     */
    public SubTask {
        if (id <= 0) {
            throw new IllegalArgumentException("SubTask.id 必须 > 0");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("SubTask.description 不能为空");
        }
        if (role == null) {
            role = AgentRole.TECHNICAL;   // 兜底——无法分类时按技术问题处理
        }
        if (expectedOutput == null) {
            expectedOutput = "";
        }
    }
}