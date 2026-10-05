package org.example.core.plan.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.core.plan.config.PlanProperties;
import org.example.core.plan.model.PlanExecutionState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 执行状态持久化——基于 Redis
 * <p>
 * 为什么持久化：
 * <ul>
 *   <li>支持中断恢复——服务重启后能接着跑</li>
 *   <li>便于排查问题——事后能看完整执行轨迹</li>
 *   <li>并发保护——同一 executionId 只能用锁跑一次</li>
 * </ul>
 */
@Slf4j
@Component
public class PlanStateStore {

    private static final String KEY_PREFIX = "PLAN_EXEC:";
    private static final String LOCK_PREFIX = "PLAN_LOCK:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PlanProperties props;

    public PlanStateStore(StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          PlanProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** 尝试获取执行锁——防止同一 executionId 被并发执行 */
    public boolean tryLock(String executionId) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                LOCK_PREFIX + executionId, "1",
                Duration.ofMinutes(5));
        return Boolean.TRUE.equals(ok);
    }

    public void releaseLock(String executionId) {
        redis.delete(LOCK_PREFIX + executionId);
    }

    /** 保存执行状态 */
    public void save(PlanExecutionState state) {
        if (!props.isPersistState()) return;
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(
                    KEY_PREFIX + state.getExecutionId(),
                    json,
                    Duration.ofHours(props.getStateTtlHours()));
        } catch (Exception e) {
            log.error("保存执行状态失败: executionId={}", state.getExecutionId(), e);
            throw new RuntimeException(e);
        }
    }

    /** 加载执行状态——用于中断恢复 */
    public PlanExecutionState load(String executionId) {
        String json = redis.opsForValue().get(KEY_PREFIX + executionId);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, PlanExecutionState.class);
        } catch (Exception e) {
            log.error("反序列化执行状态失败: executionId={}", executionId, e);
            return null;
        }
    }
}