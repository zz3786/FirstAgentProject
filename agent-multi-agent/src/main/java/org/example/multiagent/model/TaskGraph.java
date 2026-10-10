package org.example.multiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 任务图（D72）
 *
 * <h3>为什么叫 Graph 但没有真正的图结构</h3>
 * <p>
 * 名字沿用业界惯例（"任务图"是 Supervisor 模式的标准称呼）。
 * 当前 D72 阶段子任务独立，{@code TaskGraph} 实质是 {@code List<SubTask>} 的包装。
 *
 * <p>
 * <b>为什么还包装一层</b>：
 * <ul>
 *   <li>未来真加"任务依赖"时，不用改外层签名</li>
 *   <li>可以携带"全局目标"（{@code goal}）——用于聚合阶段的参考</li>
 *   <li>序列化到 RabbitMQ 时（D74）作为完整消息体，比裸 List 语义清晰</li>
 * </ul>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code goal}——对用户原始问题的简要重述。用于聚合阶段"锚定"，防止跑题</li>
 *   <li>{@code subtasks}——子任务列表</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskGraph(
        @JsonPropertyDescription("对用户原始需求的简要重述，20 字以内。用于后续校验理解是否正确")
        String goal,

        @JsonPropertyDescription("有序的子任务列表。每个子任务必须能独立完成")
        List<SubTask> subtasks
) {

    public TaskGraph {
        if (goal == null || goal.isBlank()) {
            goal = "未明确的目标";
        }
        if (subtasks == null) {
            subtasks = List.of();
        }
    }

    public boolean isEmpty() {
        return subtasks.isEmpty();
    }

    public int size() {
        return subtasks.size();
    }

    /**
     * 按 id 查子任务
     *
     * @return 对应子任务；找不到返回 null
     */
    public SubTask findById(int id) {
        return subtasks.stream()
                .filter(t -> t.id() == id)
                .findFirst()
                .orElse(null);
    }
}