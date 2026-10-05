package org.example.core.workflow.core.hitl.notifier;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.core.hitl.model.HitlTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认通知实现——只打日志
 * <p>
 * <b>为什么用 ConditionalOnProperty 而不是 ConditionalOnMissingBean</b>：
 * ConditionalOnMissingBean 只适用于 @Configuration + @Bean 方法，
 * 用在 @Component 上判断时机不可控，会导致 bean 注入失败。
 * 用配置开关代替——通过 yml 切换通知器实现。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.hitl.notifier",
        havingValue = "log",
        matchIfMissing = true   // 默认启用
)
public class LogHitlNotifier implements HitlNotifier {

    @Override
    public void notifyCreated(HitlTask task) {
        log.warn("""
                ⏸️ [HITL] 任务挂起——等待人工处理
                    taskId    = {}
                    assignee  = {}
                    reason    = {}
                    prompt    = {}
                    timeoutAt = {}
                """,
                task.getTaskId(),
                task.getAssignee(),
                task.getReason(),
                task.getHumanPrompt(),
                task.getTimeoutAt());
    }

    @Override
    public void notifyDecided(HitlTask task) {
        log.info("▶️ [HITL] 任务已决策: taskId={}, status={}, operator={}, comment={}",
                task.getTaskId(),
                task.getStatus(),
                task.getDecision() != null ? task.getDecision().operatorId() : "?",
                task.getDecision() != null ? task.getDecision().comment() : "");
    }

    @Override
    public void notifyTimeout(HitlTask task) {
        log.warn("⏱️ [HITL] 任务超时: taskId={}, 兜底策略={}",
                task.getTaskId(), task.getReason());
    }
}