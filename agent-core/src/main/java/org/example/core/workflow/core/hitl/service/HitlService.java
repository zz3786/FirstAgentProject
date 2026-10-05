package org.example.core.workflow.core.hitl.service;

import lombok.extern.slf4j.Slf4j;
import org.example.common.audit.AuditLogger;
import org.example.core.workflow.core.hitl.config.HitlProperties;
import org.example.core.workflow.core.hitl.exception.HitlException;
import org.example.core.workflow.core.hitl.model.*;
import org.example.workflow.core.hitl.model.*;
import org.example.core.workflow.core.hitl.notifier.HitlNotifier;
import org.example.core.workflow.core.hitl.store.HitlTaskStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HITL 核心服务
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>提交挂起任务</li>
 *   <li>接受人工决策</li>
 *   <li>定时扫描超时任务</li>
 *   <li>提供查询接口</li>
 * </ol>
 * <p>
 * <b>不负责</b>：具体工作流的恢复逻辑——那是各个工作流自己的事。
 * HitlService 只在决策后"发布事件"或"调用回调"，让工作流自行恢复。
 */
@Slf4j
@Service
public class HitlService {

    private final HitlTaskStore store;
    private final HitlNotifier notifier;
    private final HitlProperties props;

    public HitlService(HitlTaskStore store,
                       HitlNotifier notifier,
                       HitlProperties props) {
        this.store = store;
        this.notifier = notifier;
        this.props = props;
    }

    // ==================== 提交挂起 ====================

    /**
     * 提交挂起任务
     *
     * @return 创建的任务——调用方拿 taskId 返回给前端
     */
    public HitlTask submit(HitlContext context, String reason, String prompt) {
        return submit(context, reason, prompt, props.getDefaultAssignee());
    }

    public HitlTask submit(HitlContext context, String reason,
                           String prompt, String assignee) {
        if (!props.isEnabled()) {
            throw new HitlException(HitlException.Code.INVALID_DECISION,
                    null, "HITL 未启用");
        }

        long now = System.currentTimeMillis();
        HitlTask task = new HitlTask();
        task.setTaskId("HITL-" + UUID.randomUUID());
        task.setStatus(HitlStatus.PENDING);
        task.setReason(reason);
        task.setHumanPrompt(prompt);
        task.setContext(context);
        task.setAssignee(assignee == null ? props.getDefaultAssignee() : assignee);
        task.setCreatedAt(now);
        task.setTimeoutAt(now + props.getDefaultTimeoutMinutes() * 60_000L);

        store.save(task);
        notifier.notifyCreated(task);

        AuditLogger.sensitiveOp(
                extractTenant(context.getFullUserId()),
                context.getFullUserId(),
                "HITL_SUBMIT",
                "taskId=" + task.getTaskId()
                        + " workflow=" + context.getWorkflowType()
                        + " workflowId=" + context.getWorkflowId());

        return task;
    }

    // ==================== 人工决策 ====================

    /**
     * 批准
     */
    public HitlTask approve(String taskId, String operatorId, String operatorName, String comment) {
        return decide(taskId, HitlDecision.approve(operatorId, operatorName, comment));
    }

    /**
     * 拒绝
     */
    public HitlTask reject(String taskId, String operatorId, String operatorName, String comment) {
        return decide(taskId, HitlDecision.reject(operatorId, operatorName, comment));
    }

    /**
     * 修改后继续
     */
    public HitlTask modify(String taskId, String operatorId, String operatorName,
                           String comment, Map<String, Object> modifications) {
        return decide(taskId, HitlDecision.modify(operatorId, operatorName, comment, modifications));
    }

    /**
     * 通用决策入口
     */
    public HitlTask decide(String taskId, HitlDecision decision) {
        HitlTask task = store.load(taskId);
        if (task == null) {
            throw new HitlException(HitlException.Code.TASK_NOT_FOUND,
                    taskId, "任务不存在: " + taskId);
        }
        if (task.getStatus() != HitlStatus.PENDING) {
            throw new HitlException(HitlException.Code.TASK_ALREADY_DECIDED,
                    taskId, "任务已决策过，当前状态: " + task.getStatus());
        }

        // ① 映射决策类型到状态
        HitlStatus newStatus = switch (decision.type()) {
            case APPROVE -> HitlStatus.APPROVED;
            case REJECT -> HitlStatus.REJECTED;
            case MODIFY -> HitlStatus.MODIFIED;
        };

        // ② 更新存储
        store.updateWithDecision(taskId, newStatus, decision);

        // ③ 重新加载——拿到带决策的最新对象
        HitlTask updated = store.load(taskId);
        notifier.notifyDecided(updated);

        AuditLogger.sensitiveOp(
                extractTenant(updated.getContext().getFullUserId()),
                updated.getContext().getFullUserId(),
                "HITL_DECIDE",
                "taskId=" + taskId
                        + " type=" + decision.type()
                        + " operator=" + decision.operatorId());

        log.info("[HITL] 任务决策完成: taskId={}, type={}, operator={}",
                taskId, decision.type(), decision.operatorId());

        return updated;
    }

    // ==================== 超时扫描 ====================

    /**
     * 定时扫描超时任务
     * <p>
     * 策略：
     * <ul>
     *   <li>TIMEOUT 状态标记</li>
     *   <li>按配置执行兜底（AUTO_APPROVE / REJECT）</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${app.hitl.scan-interval-ms:60000}")
    public void scanTimeout() {
        if (!props.isEnabled() || !props.isAutoTimeoutEnabled()) {
            return;
        }
        try {
            List<HitlTask> timeoutTasks = store.listTimeout();
            for (HitlTask task : timeoutTasks) {
                handleTimeout(task);
            }
            if (!timeoutTasks.isEmpty()) {
                log.info("[HITL] 超时扫描完成: 处理 {} 个超时任务", timeoutTasks.size());
            }
        } catch (Exception e) {
            log.error("[HITL] 超时扫描异常", e);
        }
    }

    private void handleTimeout(HitlTask task) {
        String fallback = props.getTimeoutFallback();
        HitlDecision autoDecision = new HitlDecision(
                "REJECT".equalsIgnoreCase(fallback)
                        ? HitlDecisionType.REJECT : HitlDecisionType.APPROVE,
                "SYSTEM",
                "自动超时",
                "任务超时自动处理：" + fallback,
                null,
                System.currentTimeMillis()
        );

        store.updateWithDecision(task.getTaskId(), HitlStatus.TIMEOUT, autoDecision);
        notifier.notifyTimeout(store.load(task.getTaskId()));
    }

    // ==================== 查询 ====================

    public HitlTask get(String taskId) {
        return store.load(taskId);
    }

    public List<HitlTask> listPending() {
        return store.listPending();
    }

    public List<HitlTask> listByAssignee(String assignee) {
        return store.listByAssignee(assignee);
    }

    private String extractTenant(String fullUserId) {
        if (fullUserId == null) return "unknown";
        int idx = fullUserId.indexOf(':');
        return idx > 0 ? fullUserId.substring(0, idx) : fullUserId;
    }
}