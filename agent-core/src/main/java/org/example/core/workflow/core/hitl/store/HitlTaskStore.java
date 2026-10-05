package org.example.core.workflow.core.hitl.store;

import org.example.core.workflow.core.hitl.model.HitlDecision;
import org.example.core.workflow.core.hitl.model.HitlStatus;
import org.example.core.workflow.core.hitl.model.HitlTask;

import java.util.List;

/**
 * HITL 任务存储接口
 * <p>
 * 抽象出接口——便于未来从 Redis 换到 MySQL / 其他存储。
 */
public interface HitlTaskStore {

    /** 保存任务 */
    void save(HitlTask task);

    /** 按 ID 加载任务 */
    HitlTask load(String taskId);

    /** 更新任务状态 */
    void updateStatus(String taskId, HitlStatus status);

    /** 更新任务+决策——原子操作 */
    void updateWithDecision(String taskId, HitlStatus status, HitlDecision decision);

    /** 删除任务 */
    void delete(String taskId);

    /** 列出所有 PENDING 任务 */
    List<HitlTask> listPending();

    /** 列出指定处理人的任务 */
    List<HitlTask> listByAssignee(String assignee);

    /** 列出所有超时但未处理的任务 */
    List<HitlTask> listTimeout();
}