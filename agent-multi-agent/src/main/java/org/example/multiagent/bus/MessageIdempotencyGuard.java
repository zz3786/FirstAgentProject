package org.example.multiagent.bus;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.bus.config.RabbitBusProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消息幂等守卫（D74）
 *
 * <h3>为什么需要幂等</h3>
 * <p>
 * RabbitMQ 提供"至少一次"（at-least-once）投递语义——<b>不保证不重复</b>。
 * 可能发生的重复场景：
 * <ul>
 *   <li>消费者处理完但 ack 丢包——MQ 重新投递</li>
 *   <li>消费者处理超时——MQ 判定失败重新投递（虽然消费者还在处理）</li>
 *   <li>网络抖动导致 ack 未到达——同上</li>
 * </ul>
 * <p>
 * 用 messageId + Redis SETNX 做"已处理"标记——重复消息直接被丢弃。
 *
 * <h3>为什么用 Redis 而非本地缓存</h3>
 * <p>
 * 多实例部署时，"实例 A 处理过的消息被投递到实例 B"是常见场景。
 * 本地缓存各管各的，跨实例失效。Redis 是共享状态，全实例可见。
 *
 * <h3>TTL 怎么选</h3>
 * <p>
 * 默认 600 秒（10 分钟）——理由：
 * <ul>
 *   <li>够长：覆盖 MQ 重投的最坏延迟（通常秒级到分钟级）</li>
 *   <li>够短：不无限膨胀 Redis——每天百万消息也只占几十 MB</li>
 * </ul>
 * <p>
 * 如果发现重复消费在生产中仍偶发，说明 TTL 太短——调到 3600 秒。
 *
 * <h3>SETNX 失败的处理</h3>
 * <p>
 * 已经标记过 = 重复消息 → 返回 false，消费方跳过。
 * <p>
 * Redis 不可用时——<b>默认放行</b>（"fail-open"）：
 * 幂等是优化，不是核心逻辑。Redis 挂时不应阻断业务。
 */
@Slf4j
@Component
public class MessageIdempotencyGuard {

    private final StringRedisTemplate redis;
    private final RabbitBusProperties props;

    public MessageIdempotencyGuard(StringRedisTemplate redis,
                                   RabbitBusProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 尝试标记消息已处理
     *
     * @param messageId 消息 ID
     * @return true = 首次处理（继续）；false = 重复消息（跳过）
     */
    public boolean tryMarkProcessed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            // 无 messageId 无法去重——放行
            log.warn("[Idempotency] 消息无 messageId，无法去重——放行");
            return true;
        }

        String key = props.getIdempotency().getKeyPrefix() + messageId;

        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    key, "1",
                    Duration.ofSeconds(props.getIdempotency().getTtlSeconds()));

            if (Boolean.TRUE.equals(ok)) {
                return true;   // 首次——继续处理
            }

            log.info("[Idempotency] 重复消息，跳过: messageId={}", messageId);
            return false;

        } catch (Exception e) {
            // ★ fail-open——Redis 异常时放行，不阻断业务
            log.warn("[Idempotency] Redis 异常，fail-open 放行: messageId={}, err={}",
                    messageId, e.getMessage());
            return true;
        }
    }

    /**
     * 主动释放标记——用于"处理失败需要重试"场景
     * <p>
     * 注意：正常流程不该调用——只在"业务确定要重试"时主动删标记。
     */
    public void releaseMark(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            redis.delete(props.getIdempotency().getKeyPrefix() + messageId);
        } catch (Exception e) {
            log.warn("[Idempotency] 释放标记失败: messageId={}", messageId, e);
        }
    }
}