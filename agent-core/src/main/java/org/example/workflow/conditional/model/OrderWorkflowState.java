package org.example.workflow.conditional.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单工作流状态——可变的执行上下文
 * <p>
 * 为什么用 @Data 而不是 record：
 * 每个节点都要往里面写数据（比如 retrievedOrder, finalMessage）——
 * 需要用可变对象承载"过程状态"。
 */
@Data
public class OrderWorkflowState {

    // ========== 输入（不可变） ==========
    private final String orderId;
    private final String fullUserId;
    private final String executionId;

    // ========== 步骤产物（逐步填充） ==========
    /** Step 1 输出：查询到的订单 */
    private OrderInfo orderInfo;

    /** Step 2 输出：决策走的分支名 */
    private String branchTaken;

    /** Step 3 输出：业务动作的结果（付款提醒/物流/等） */
    private String branchResult;

    /** Step 4 输出：审计日志的 ID */
    private String auditId;

    /** 最终给用户的答复 */
    private String finalMessage;

    // ========== 可观测 ==========
    private final List<WorkflowTrace> traces = new ArrayList<>();
    private final long startTime = System.currentTimeMillis();
    private final long deadline;
    private String status = "RUNNING";    // RUNNING / SUCCESS / FAILED / TIMEOUT

    public OrderWorkflowState(String orderId, String fullUserId,
                              String executionId, long totalTimeoutMs) {
        this.orderId = orderId;
        this.fullUserId = fullUserId;
        this.executionId = executionId;
        this.deadline = System.currentTimeMillis() + totalTimeoutMs;
    }

    /** 追加轨迹 */
    public void addTrace(WorkflowTrace trace) {
        traces.add(trace);
    }

    /** 是否已超时 */
    public boolean isTimeout() {
        return System.currentTimeMillis() > deadline;
    }

    /** 是否已结束（成功或失败） */
    public boolean isFinished() {
        return !"RUNNING".equals(status);
    }

    /** 总耗时 */
    public long totalCostMs() {
        return System.currentTimeMillis() - startTime;
    }
}