package org.example.workflow.core.hitl.notifier;

import org.example.workflow.core.hitl.model.HitlTask;

/**
 * HITL 通知器接口
 * <p>
 * 生产可以接：钉钉/企微/邮件/工单系统
 * 教学用默认实现：日志
 */
public interface HitlNotifier {

    /** 任务创建时通知 */
    void notifyCreated(HitlTask task);

    /** 任务决策后通知 */
    void notifyDecided(HitlTask task);

    /** 任务超时通知 */
    void notifyTimeout(HitlTask task);
}