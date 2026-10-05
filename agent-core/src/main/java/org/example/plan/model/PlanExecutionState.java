package org.example.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行状态——可序列化到 Redis，支持中断恢复
 * <p>
 * 字段设计原则：只存"恢复必需"的数据，不存大对象（比如原始文档）。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanExecutionState {

    // ========== 不可变标识 ==========
    private String executionId;
    private String conversationId;
    private String fullUserId;
    private String userInput;

    // ========== 计划演变 ==========
    private Plan currentPlan;                          // 当前生效的计划
    private int nextStepIndex = 0;                     // 下一个待执行的 step 索引
    private int replanCount = 0;

    // ========== 结果累积 ==========
    private List<StepResult> stepResults = new ArrayList<>();

    // ========== 状态与时间 ==========
    private PlanStatus status = PlanStatus.PLANNING;
    private long startTime;
    private long deadline;                             // 整体超时的时间戳
    private String finalAnswer;

    // ========== 便捷方法 ==========

    public boolean hasNextStep() {
        return currentPlan != null && nextStepIndex < currentPlan.steps().size();
    }

    public PlanStep nextStep() {
        return currentPlan.steps().get(nextStepIndex);
    }

    public void advanceStep() {
        nextStepIndex++;
    }

    public boolean isTimeout() {
        return System.currentTimeMillis() > deadline;
    }

    public boolean canReplan(int maxReplans) {
        return replanCount < maxReplans;
    }

    /** 取前序步骤的输出——执行当前步骤时作为上下文 */
    public List<StepResult> previousSuccessResults() {
        return stepResults.stream()
                .filter(r -> "SUCCESS".equals(r.status()))
                .toList();
    }

    /** 追加结果——按 stepId 去重（重规划后同 id 覆盖） */
    public void addResult(StepResult result) {
        stepResults.removeIf(r -> r.stepId() == result.stepId());
        stepResults.add(result);
    }
}