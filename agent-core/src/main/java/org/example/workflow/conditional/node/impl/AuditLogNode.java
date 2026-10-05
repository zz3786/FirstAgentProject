package org.example.workflow.conditional.node.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.common.audit.AuditLogger;
import org.example.workflow.conditional.config.ConditionalWorkflowProperties;
import org.example.workflow.conditional.model.OrderWorkflowState;
import org.example.workflow.conditional.node.AbstractWorkflowNode;
import org.springframework.stereotype.Component;

/**
 * 步骤 ④：审计日志
 * <p>
 * 记录本次订单处理——走的分支、耗时、结果。
 */
@Slf4j
@Component
public class AuditLogNode extends AbstractWorkflowNode {

    public AuditLogNode(ConditionalWorkflowProperties props) {
        super(props);
    }

    @Override
    public String name() {
        return "AuditLogNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String branch = state.getBranchTaken();
        String result = state.getBranchResult();

        // 用已有的 AuditLogger 记录
        AuditLogger.sensitiveOp(
                extractTenant(state.getFullUserId()),
                state.getFullUserId(),
                "ORDER_WORKFLOW",
                "order=" + state.getOrderId()
                        + " branch=" + branch
                        + " costMs=" + state.totalCostMs());

        String auditId = "AUDIT-" + System.currentTimeMillis();
        state.setAuditId(auditId);

        log.info("📋 审计记录完成：{}", auditId);
        return "审计记录：" + auditId;
    }

    /** 从 fullUserId "hospital-a:user-alice" 提取 tenantId */
    private String extractTenant(String fullUserId) {
        int idx = fullUserId.indexOf(':');
        return idx > 0 ? fullUserId.substring(0, idx) : "default";
    }

    /** 审计失败——不阻断主流程 */
    @Override
    protected boolean isDegradable() {
        return true;
    }

    @Override
    protected String degradedResult(OrderWorkflowState state) {
        return "审计记录写入失败（不影响业务流程）";
    }
}