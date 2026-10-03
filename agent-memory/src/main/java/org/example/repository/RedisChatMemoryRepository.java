package org.example.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.config.ConversationMemoryProperties;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Redis 会话记忆仓库
 * <p>
 * <b>D54 生产级租户隔离</b>：所有"按前缀查询"用 SCAN——绝不用 KEYS。
 * <p>
 * <b>核心设计</b>：
 * <ul>
 *   <li>游标分批——SCAN + COUNT，不阻塞 Redis</li>
 *   <li>三重保护——批大小、超时、返回上限，参数外置到 yml</li>
 *   <li>pattern 转义——防 tenantId 含 * ? 等通配符导致跨租户泄露</li>
 *   <li>异常降级——返回空 List，不阻断主流程</li>
 * </ul>
 */
@Slf4j
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String PREFIX = "CHAT:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties props;

    public RedisChatMemoryRepository(StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     ConversationMemoryProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @PostConstruct
    public void printConfig() {
        log.info("[RedisChatMemoryRepository] SCAN 配置: batchSize={}, timeout={}s, maxResults={}",
                props.getScanBatchSize(),
                props.getScanTimeoutSeconds(),
                props.getMaxConversationIds());
    }

    // ==================== Spring AI 接口实现 ====================

    @Override
    public List<String> findConversationIds() {
        // ⚠️ 接口方法没有租户参数——不推荐业务直接用
        //   保留兼容——但全量扫描有风险——限制返回数
        log.warn("[AUDIT] findConversationIds() 被调用——全量扫描——建议改用 findConversationIdsByTenant");
        return scanKeys(PREFIX + "*", props.getMaxConversationIds());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> jsons = redis.opsForList().range(PREFIX + conversationId, 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return List.of();
        }
        return jsons.stream()
                .filter(json -> json != null && !json.isBlank())
                .map(json -> {
                    try {
                        MessageDto dto = objectMapper.readValue(json, MessageDto.class);
                        return dto.toMessage();
                    } catch (Exception e) {
                        log.error("反序列化消息失败, 原始内容: [{}]", json, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = PREFIX + conversationId;
        redis.delete(key);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            for (Message msg : messages) {
                MessageDto dto = MessageDto.from(msg);
                String json = objectMapper.writeValueAsString(dto);
                redis.opsForList().rightPush(key, json);
            }
            redis.expire(key, TTL);
        } catch (Exception e) {
            throw new RuntimeException("序列化消息失败", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(PREFIX + conversationId);
    }

    // ==================== D54 租户隔离查询 ====================

    /**
     * 按租户查会话 ID 列表（生产推荐）
     * <p>
     * 匹配 pattern：{@code CHAT:{tenantId}:*}
     */
    public List<String> findConversationIdsByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        String pattern = PREFIX + escapePattern(tenantId) + ":*";
        return scanKeys(pattern, props.getMaxConversationIds());
    }

    /**
     * 按租户 + 用户查会话 ID（最精确——推荐业务用这个）
     * <p>
     * 匹配 pattern：{@code CHAT:{tenantId}:{userId}:*}
     */
    public List<String> findConversationIdsByUser(String tenantId, String userId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        String pattern = PREFIX + escapePattern(tenantId)
                + ":" + escapePattern(userId) + ":*";
        return scanKeys(pattern, props.getMaxConversationIds());
    }

    // ==================== 核心：SCAN 实现 ====================

    /**
     * 用 SCAN 遍历匹配的 key——绝不阻塞 Redis
     * <p>
     * <b>三重保护</b>：
     * <ol>
     *   <li>SCAN 分批——COUNT scanBatchSize</li>
     *   <li>超时保护——总耗时超 scanTimeoutSeconds 停止</li>
     *   <li>上限截断——maxResults 达到即停止</li>
     * </ol>
     *
     * @param pattern    匹配模式
     * @param maxResults 最大返回数
     */
    private List<String> scanKeys(String pattern, int maxResults) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + props.getScanTimeoutSeconds() * 1000L;
        int batchSize = props.getScanBatchSize();

        List<String> result = new ArrayList<>();

        try {
            redis.execute((RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(batchSize)
                        .build();

                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        // ① 超时保护
                        if (System.currentTimeMillis() > deadline) {
                            log.warn("[SCAN] 超时保护触发: pattern={}, 已扫 {} 条, 耗时 {}ms",
                                    pattern, result.size(),
                                    System.currentTimeMillis() - startTime);
                            break;
                        }

                        // ② 上限保护
                        if (result.size() >= maxResults) {
                            log.warn("[SCAN] 达到上限: pattern={}, maxResults={}",
                                    pattern, maxResults);
                            break;
                        }

                        byte[] keyBytes = cursor.next();
                        if (keyBytes == null) {
                            continue;
                        }
                        String key = new String(keyBytes, StandardCharsets.UTF_8);
                        if (key.startsWith(PREFIX)) {
                            result.add(key.substring(PREFIX.length()));
                        }
                    }
                } catch (Exception e) {
                    log.error("[SCAN] 遍历异常: pattern={}", pattern, e);
                }
                return null;
            });

            long cost = System.currentTimeMillis() - startTime;
            log.debug("[SCAN] 完成: pattern={}, 返回 {} 条, 耗时 {}ms",
                    pattern, result.size(), cost);

            return result;

        } catch (Exception e) {
            // ★ 降级——返回空，不抛异常
            log.error("[SCAN] 失败，降级为空: pattern={}", pattern, e);
            return List.of();
        }
    }

    /**
     * 转义 Redis pattern 特殊字符
     * <p>
     * Redis pattern 支持 {@code * ? [ ]} 通配符——
     * 如果 tenantId 里含这些字符，会匹配到非预期 key（跨租户泄露）。
     * 转义后当字面量用。
     */
    private String escapePattern(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }
}