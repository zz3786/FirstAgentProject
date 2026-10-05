package org.example.core.workflow.conditional.order;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.config.OrderWorkflowProperties;
import org.example.core.workflow.conditional.order.model.OrderInfo;
import org.example.core.workflow.conditional.order.model.OrderStatus;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.exception.WorkflowNodeException;
import org.example.core.workflow.core.hitl.model.*;
import org.example.core.workflow.core.model.NodeResult;
import org.example.core.workflow.core.model.WorkflowTrace;
import org.example.core.workflow.core.node.WorkflowNode;
import org.example.core.workflow.conditional.order.nodes.AuditLogNode;
import org.example.core.workflow.conditional.order.nodes.DecisionNode;
import org.example.core.workflow.conditional.order.nodes.LoadOrderNode;
import org.example.core.workflow.conditional.order.nodes.HitlApprovalNode;
import org.example.core.workflow.conditional.order.nodes.ParallelFetchNode;
import org.example.core.workflow.core.registry.WorkflowNodeRegistry;
import org.example.core.workflow.core.hitl.exception.HitlException;
import org.example.core.workflow.core.hitl.exception.WorkflowSuspendedException;
import org.example.workflow.core.hitl.model.*;
import org.example.core.workflow.core.hitl.service.HitlService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 订单条件工作流——D59 主入口
 * <p>
 * <b>流程</b>：
 * <pre>
 *   ┌─ 幂等锁
 *   │
 *   ├─ ① LoadOrderNode       查询订单
 *   │
 *   ├─ ② DecisionNode        决策（内部调 Router 选分支）
 *   │     ↓
 *   │   ┌──────────────────────────────┐
 *   │   │ ③ 根据 branchTaken 动态执行：│
 *   │   │   PaymentReminderNode        │
 *   │   │   LogisticsTrackingNode      │
 *   │   │   SatisfactionSurveyNode     │
 *   │   │   EscalateToHumanNode        │
 *   │   └──────────────────────────────┘
 *   │
 *   ├─ ④ AuditLogNode        审计
 *   │
 *   └─ 释放锁
 * </pre>
 */
@Slf4j
@Service
public class OrderConditionalWorkflow {

    private static final String LOCK_PREFIX = "ORDER_WF_LOCK:";

    private final HitlApprovalNode hitlApprovalNode;
    private final HitlService hitlService;
    private final ParallelFetchNode parallelFetchNode;
    private final LoadOrderNode loadOrderNode;
    private final DecisionNode decisionNode;
    private final AuditLogNode auditLogNode;
    private final WorkflowNodeRegistry registry;
    private final OrderWorkflowProperties props;
    private final StringRedisTemplate redis;

    public OrderConditionalWorkflow(HitlApprovalNode hitlApprovalNode, HitlService hitlService, ParallelFetchNode parallelFetchNode, LoadOrderNode loadOrderNode,
                                    DecisionNode decisionNode,
                                    AuditLogNode auditLogNode,
                                    WorkflowNodeRegistry registry,
                                    OrderWorkflowProperties props,
                                    StringRedisTemplate redis) {
        this.hitlApprovalNode = hitlApprovalNode;
        this.hitlService = hitlService;
        this.parallelFetchNode = parallelFetchNode;
        this.loadOrderNode = loadOrderNode;
        this.decisionNode = decisionNode;
        this.auditLogNode = auditLogNode;
        this.registry = registry;
        this.props = props;
        this.redis = redis;
    }

    /**
     * 处理一个订单
     */
    public Map<String, Object> process(String orderId, String fullUserId) {
        if (!props.isEnabled()) {
            return Map.of("success", false, "message", "工作流未启用");
        }

        String executionId = UUID.randomUUID().toString();
        OrderWorkflowState state = new OrderWorkflowState(orderId, fullUserId, executionId, props.getTotalTimeoutMs());

        // ① 幂等锁——防止同一订单重复处理
        String lockKey = LOCK_PREFIX + orderId;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, executionId, Duration.ofSeconds(props.getIdempotencyLockSeconds()));

        if (!Boolean.TRUE.equals(locked)) {
            log.warn("订单 {} 正在处理中，拒绝重复请求", orderId);
            return Map.of("success", false, "message", "该订单正在处理中");
        }

        try {
            // ② 顺序 + 条件混合执行
            runNode(parallelFetchNode, state);       // 顺序：查询订单
            runNode(decisionNode, state);        // 顺序：决策

            // ★ 新增：在条件分支之前尝试 HITL 审批
            //   如果不需要审批 → shouldEnter 返回 false → 跳过
            //   如果需要 → doExecute 抛 WorkflowSuspendedException
            runNode(hitlApprovalNode, state);

            // ★ 条件分支：根据决策结果动态执行
            String branchName = state.getBranchTaken();
            if (branchName == null || branchName.isBlank()) {
                throw new WorkflowNodeException("DecisionNode","决策节点未产出分支名");
            }
            WorkflowNode branchNode = registry.get(branchName);
            log.info("▶️ 进入条件分支：{}", branchName);

            runNode(branchNode, state);
            runNode(auditLogNode, state);        // 顺序：审计

            state.setStatus("SUCCESS");
            state.setFinalMessage(buildFinalMessage(state));
            return buildResponse(state);

        } catch (WorkflowSuspendedException suspended) {
            // ★ 挂起——不是失败
            state.setStatus("SUSPENDED");
            log.info("⏸️ 工作流挂起: order={} taskId={}",
                    orderId, suspended.getTaskId());
            return Map.of(
                    "success", true,
                    "status", "SUSPENDED",
                    "taskId", suspended.getTaskId(),
                    "message", "订单处理已提交人工审批，请等待处理",
                    "executionId", state.getExecutionId()
            );

        } catch (Exception e) {
            log.error("订单 {} 处理失败", orderId, e);
            state.setStatus("FAILED");
            state.setFinalMessage("处理失败：" + e.getMessage());
            state.addTrace(WorkflowTrace.failed("Workflow", state.totalCostMs(),
                    e.getMessage()));
            return buildResponse(state);

        } finally {
            // ★ 锁要注意：挂起状态下要延长锁时间
            //   或者改成"任务完成后才释放锁"
            if (!state.isFinished()) {
                // 挂起时保留锁——但要考虑锁超时
            }
            redis.delete(lockKey);
        }
    }

    /**
     * 执行单个节点——统一封装超时检查 + trace
     */
    private void runNode(WorkflowNode node, OrderWorkflowState state) {
        // 整体超时检查
        if (state.isTimeout()) {
            log.warn("整体超时，中止: executionId={}", state.getExecutionId());
            state.setStatus("TIMEOUT");
            throw new WorkflowNodeException("Workflow", "整体超时");
        }

        long start = System.currentTimeMillis();

        NodeResult result = node.execute(state);

        // 幂等/重入保护：如果节点返回 SKIPPED，日志区分
        if ("SKIPPED".equals(result.output())) {
            log.debug("[{}] 已跳过", node.name());
        }
    }

    /** 组装给用户的最终答复 */
    private String buildFinalMessage(OrderWorkflowState state) {
        return "订单 " + state.getOrderId() + " 处理完成。\n"
                + "分支：" + state.getBranchTaken() + "\n"
                + state.getBranchResult();
    }

    /** 组装响应结构 */
    private Map<String, Object> buildResponse(OrderWorkflowState state) {
        return Map.of(
                "success", "SUCCESS".equals(state.getStatus()),
                "executionId", state.getExecutionId(),
                "orderId", state.getOrderId(),
                "status", state.getStatus(),
                "branchTaken", state.getBranchTaken() == null ? "" : state.getBranchTaken(),
                "finalMessage", state.getFinalMessage() == null ? "" : state.getFinalMessage(),
                "traces", state.getTraces(),
                "totalCostMs", state.totalCostMs()
        );
    }

    /**
     * 从人工决策恢复执行
     * <p>
     * 由 HitlController 在人工决策后调用。
     *
     * @param taskId  HITL 任务 ID
     * @return 最终结果
     */
    public Map<String, Object> resume(String taskId) {
        // ① 加载 HITL 任务
        HitlTask task = hitlService.get(taskId);
        if (task == null) {
            throw new HitlException(HitlException.Code.TASK_NOT_FOUND,
                    taskId, "任务不存在");
        }
        if (task.getStatus() == HitlStatus.PENDING) {
            throw new HitlException(HitlException.Code.INVALID_DECISION,
                    taskId, "任务尚未决策，无法恢复");
        }

        HitlContext ctx = task.getContext();
        String orderId = ctx.getWorkflowId();

        // ② 幂等检查——防止重复恢复
        String resumeLockKey = "HITL_RESUME_LOCK:" + taskId;
        Boolean locked = redis.opsForValue().setIfAbsent(resumeLockKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(locked)) {
            log.warn("[HITL] 任务 {} 正在恢复中，拒绝重复请求", taskId);
            return Map.of("success", false, "message", "该任务正在恢复中");
        }

        try {
            // ③ 重建 state
            OrderWorkflowState state = rebuildState(ctx);

            // ④ 处理决策
            HitlDecision decision = task.getDecision();
            if (decision.type() == HitlDecisionType.REJECT) {
                log.info("[HITL] 任务被拒绝: taskId={}, comment={}",
                        taskId, decision.comment());
                state.setStatus("REJECTED");
                state.setFinalMessage("人工审批未通过：" + decision.comment());
                return buildResponse(state);
            }

            // APPROVE 或 MODIFY——都继续执行
            if (decision.type() == HitlDecisionType.MODIFY && decision.modifications() != null) {
                // 应用人工修改
                applyModifications(state, decision.modifications());
            }

            // ⑤ 继续执行分支节点
            String branchName = state.getBranchTaken();
            WorkflowNode branchNode = registry.get(branchName);
            runNode(branchNode, state);

            // ⑥ 审计节点
            runNode(auditLogNode, state);

            state.setStatus("SUCCESS");
            state.setFinalMessage(buildFinalMessage(state));
            log.info("[HITL] 恢复执行完成: taskId={}, order={}", taskId, orderId);
            return buildResponse(state);

        } catch (Exception e) {
            log.error("[HITL] 恢复执行失败: taskId={}", taskId, e);
            return Map.of("success", false, "message", "恢复执行失败: " + e.getMessage());
        } finally {
            redis.delete(resumeLockKey);
        }
    }

    /**
     * 从 HitlContext 重建 OrderWorkflowState
     */
    private OrderWorkflowState rebuildState(HitlContext ctx) {
        long now = System.currentTimeMillis();
        long remainingTimeout = Math.max(1000, ctx.getWorkflowDeadline() - now);

        OrderWorkflowState state = new OrderWorkflowState(
                ctx.getWorkflowId(),
                ctx.getFullUserId(),
                ctx.getExecutionId(),
                remainingTimeout
        );

        // 重建 OrderInfo
        Map<String, Object> p = ctx.getPayload();
        if (p != null) {
            OrderStatus status = OrderStatus.fromCode((String) p.get("status"));
            OrderInfo info = new OrderInfo(
                    (String) p.get("orderId"),
                    status,
                    (String) p.get("buyerName"),
                    "13800000000",   // 联系方式实际也要存，这里简化
                    ((Number) p.getOrDefault("amount", 0.0)).doubleValue()
            );
            state.setOrderInfo(info);
        }
        state.setBranchTaken(ctx.getPendingAction());
        return state;
    }

    /**
     * 应用人工修改
     */
    private void applyModifications(OrderWorkflowState state, Map<String, Object> mods) {
        // 示例：如果人工修改了金额，更新到 state
        if (mods.containsKey("amount") && state.getOrderInfo() != null) {
            double newAmount = ((Number) mods.get("amount")).doubleValue();
            OrderInfo old = state.getOrderInfo();
            OrderInfo updated = new OrderInfo(
                    old.orderId(), old.status(),
                    old.buyerName(), old.buyerContact(), newAmount);
            state.setOrderInfo(updated);
            log.info("[HITL] 应用人工修改: amount={}", newAmount);
        }
    }
}