package org.example.core.workflow.conditional.order.nodes;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.config.WorkflowEngineProperties;
import org.example.core.workflow.core.node.AbstractWorkflowNode;
import org.example.core.workflow.core.hitl.config.HitlProperties;
import org.example.core.workflow.core.hitl.exception.WorkflowSuspendedException;
import org.example.core.workflow.core.hitl.model.HitlContext;
import org.example.core.workflow.core.hitl.model.HitlTask;
import org.example.core.workflow.core.hitl.service.HitlService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HITL 审批节点——订单工作流中插入的"挂起"点
 *
 * <h3>触发条件</h3>
 * <pre>
 *   只有当前分支在 approvalRequiredBranches 白名单里 或
 *   当前状态在 approvalRequiredStatuses 白名单里
 *   才挂起等待人工审批。
 * </pre>
 *
 * <h3>执行流程</h3>
 * <pre>
 *   检查是否需要审批?
 *     ├─ 否 → 跳过（shouldEnter=false）
 *     └─ 是 → 创建 HITL 任务 → 序列化上下文 → 抛 WorkflowSuspendedException
 *              ↓
 *             上层捕获 → 返回 SUSPENDED 给用户
 *              ↓
 *             人工决策 → 触发 resume → 从 Redis 恢复 → 继续后续节点
 * </pre>
 */
@Slf4j
@Component
public class HitlApprovalNode extends AbstractWorkflowNode {

    private final HitlService hitlService;
    private final HitlProperties hitlProps;

    public HitlApprovalNode(WorkflowEngineProperties props,
                            HitlService hitlService,
                            HitlProperties hitlProps) {
        super(props);
        this.hitlService = hitlService;
        this.hitlProps = hitlProps;
    }

    @Override
    public String name() {
        return "HitlApprovalNode";
    }

    /**
     * 是否进入——只有特定分支才需要审批
     * 该子类方法在 AbstractWorkflowNode 被调用
     */
    @Override
    public boolean shouldEnter(OrderWorkflowState state) {
        if (!hitlProps.isEnabled()) return false;

        // 检查分支白名单
        String branch = state.getBranchTaken();
        if (branch != null && hitlProps.getApprovalRequiredBranches().contains(branch)) {
            log.info("[HITL] 分支 {} 在白名单里——需要人工审批", branch);
            return true;
        }

        // 检查状态白名单
        if (state.getOrderInfo() != null) {
            String status = state.getOrderInfo().status().name();
            if (hitlProps.getApprovalRequiredStatuses().contains(status)) {
                log.info("[HITL] 状态 {} 在白名单里——需要人工审批", status);
                return true;
            }
        }

        return false;
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        // ① 构造上下文快照
        HitlContext ctx = buildContext(state);

        // ② 提交挂起任务
        String reason = String.format("订单 %s 需要人工审批（分支：%s）",
                state.getOrderId(), state.getBranchTaken());
        String prompt = String.format("""
                订单处理请求需要您审批。
                
                订单号：%s
                买家：%s
                金额：%.2f 元
                状态：%s
                待执行动作：%s
                
                是否批准继续处理？
                """,
                state.getOrderId(),
                state.getOrderInfo().buyerName(),
                state.getOrderInfo().amount(),
                state.getOrderInfo().status().getLabel(),
                state.getBranchTaken());

        HitlTask task = hitlService.submit(ctx, reason, prompt);
        log.warn("⏸️ 工作流已挂起，等待人工决策: taskId={}", task.getTaskId());

        // ③ 抛挂起异常——中断当前执行流
        throw new WorkflowSuspendedException(task.getTaskId(),
                "工作流挂起: " + task.getTaskId());
    }

    private HitlContext buildContext(OrderWorkflowState state) {
        HitlContext ctx = new HitlContext();
        ctx.setWorkflowType("order-workflow");
        ctx.setWorkflowId(state.getOrderId());
        ctx.setExecutionId(state.getExecutionId());
        ctx.setFullUserId(state.getFullUserId());
        ctx.setCurrentStep("DecisionNode");
        ctx.setPendingAction(state.getBranchTaken());
        ctx.setCompletedNodes(java.util.List.of("LoadOrderNode", "DecisionNode"));
        ctx.setRemainingNodes(java.util.List.of(state.getBranchTaken(), "AuditLogNode"));
        ctx.setWorkflowStartTime(state.getStartTime());
        ctx.setWorkflowDeadline(state.getDeadline());

        // 业务数据快照
        Map<String, Object> payload = new java.util.HashMap<>();
        if (state.getOrderInfo() != null) {
            payload.put("orderId", state.getOrderInfo().orderId());
            payload.put("status", state.getOrderInfo().status().name());
            payload.put("buyerName", state.getOrderInfo().buyerName());
            payload.put("amount", state.getOrderInfo().amount());
        }
        ctx.setPayload(payload);
        return ctx;
    }

    /** 挂起不是"失败"——走 AbstractWorkflowNode 的异常路径不合适 */
    @Override
    protected boolean isDegradable() {
        return false;
    }
}