package org.example.workflow.core.hitl.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.core.hitl.config.HitlProperties;
import org.example.workflow.core.hitl.exception.HitlException;
import org.example.workflow.core.hitl.model.HitlDecision;
import org.example.workflow.core.hitl.model.HitlStatus;
import org.example.workflow.core.hitl.model.HitlTask;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 实现的任务存储
 * <p>
 * <b>数据结构</b>：
 * <pre>
 *   HITL_TASK:{taskId}       → JSON 字符串（Hash 也行，字符串更简单）
 *   HITL_PENDING             → Set（所有 PENDING 任务的 taskId）
 *   HITL_TIMEOUT             → ZSet（score = timeoutAt，用于快速扫超时）
 * </pre>
 */
@Slf4j
@Component
public class RedisHitlTaskStore implements HitlTaskStore {

    private static final String KEY_PREFIX = "HITL_TASK:";
    private static final String PENDING_SET = "HITL_PENDING";
    private static final String TIMEOUT_ZSET = "HITL_TIMEOUT";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final HitlProperties props;

    public RedisHitlTaskStore(StringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              HitlProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public void save(HitlTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            String key = KEY_PREFIX + task.getTaskId();

            // ① 存主对象
            redis.opsForValue().set(key, json,
                    Duration.ofHours(props.getTaskHistoryTtlHours()));

            // ② 加入 PENDING 集合
            if (task.getStatus() == HitlStatus.PENDING) {
                redis.opsForSet().add(PENDING_SET, task.getTaskId());
                // ③ 加入超时 ZSet（score = 超时时间戳）
                redis.opsForZSet().add(TIMEOUT_ZSET, task.getTaskId(),
                        task.getTimeoutAt());
            }

            log.info("[HITL] 任务已保存: taskId={}, status={}",
                    task.getTaskId(), task.getStatus());

        } catch (Exception e) {
            throw new HitlException(HitlException.Code.STORE_ERROR,
                    task.getTaskId(), "保存任务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public HitlTask load(String taskId) {
        String json = redis.opsForValue().get(KEY_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, HitlTask.class);
        } catch (Exception e) {
            log.error("[HITL] 反序列化失败: taskId={}", taskId, e);
            return null;
        }
    }

    @Override
    public void updateStatus(String taskId, HitlStatus status) {
        updateWithDecision(taskId, status, null);
    }

    @Override
    public void updateWithDecision(String taskId, HitlStatus status, HitlDecision decision) {
        HitlTask task = load(taskId);
        if (task == null) {
            throw new HitlException(HitlException.Code.TASK_NOT_FOUND,
                    taskId, "任务不存在: " + taskId);
        }

        task.setStatus(status);
        if (decision != null) {
            task.setDecision(decision);
        }

        try {
            String json = objectMapper.writeValueAsString(task);
            redis.opsForValue().set(KEY_PREFIX + taskId, json,
                    Duration.ofHours(props.getTaskHistoryTtlHours()));

            // 从 PENDING 和 ZSet 移除
            if (status.isTerminal()) {
                redis.opsForSet().remove(PENDING_SET, taskId);
                redis.opsForZSet().remove(TIMEOUT_ZSET, taskId);
            }
        } catch (Exception e) {
            throw new HitlException(HitlException.Code.STORE_ERROR,
                    taskId, "更新任务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String taskId) {
        redis.delete(KEY_PREFIX + taskId);
        redis.opsForSet().remove(PENDING_SET, taskId);
        redis.opsForZSet().remove(TIMEOUT_ZSET, taskId);
    }

    @Override
    public List<HitlTask> listPending() {
        Set<String> ids = redis.opsForSet().members(PENDING_SET);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream()
                .map(this::load)
                .filter(t -> t != null && t.getStatus() == HitlStatus.PENDING)
                .collect(Collectors.toList());
    }

    @Override
    public List<HitlTask> listByAssignee(String assignee) {
        return listPending().stream()
                .filter(t -> assignee.equals(t.getAssignee()))
                .collect(Collectors.toList());
    }

    @Override
    public List<HitlTask> listTimeout() {
        long now = System.currentTimeMillis();
        Set<String> ids = redis.opsForZSet().rangeByScore(TIMEOUT_ZSET, 0, now);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream()
                .map(this::load)
                .filter(t -> t != null && t.getStatus() == HitlStatus.PENDING)
                .collect(Collectors.toList());
    }
}