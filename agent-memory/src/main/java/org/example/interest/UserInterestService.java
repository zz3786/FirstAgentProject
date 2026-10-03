package org.example.interest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.UserInterestProperties;
import org.example.utils.SensitiveDataMasker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * D53 用户兴趣标签服务
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>维护用户关注的"技术领域标签"</li>
 *   <li>提供按权重排序的 TopN 标签</li>
 *   <li>渲染成 SystemMessage 片段，供 Advisor 注入</li>
 * </ol>
 * <p>
 * <b>存储结构</b>：
 * <pre>
 * USER_INTEREST:{userId} (Redis Hash)
 *   ├── "Spring AI" → {"tag":"Spring AI","weight":0.75,"lastSeenAt":...,"hitCount":5}
 *   ├── "RAG"       → {...}
 *   └── "MCP"       → {...}
 * </pre>
 * <p>
 * <b>读取失败降级</b>：任何异常 → 返回空列表——兴趣注入失败不该阻断对话。
 */
@Slf4j
@Service
public class UserInterestService {

    private static final String PREFIX = "USER_INTEREST:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserInterestProperties props;

    public UserInterestService(StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               UserInterestProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    // ==================== 写入 ====================

    /**
     * 记录一个兴趣标签
     * <p>
     * 已存在 → 权重 +increment；不存在 → 以初始权重创建。
     */
    public void record(String userId, String rawTag) {
        if (!props.isEnabled() || userId == null || rawTag == null) {
            return;
        }

        String tag = normalize(rawTag);
        if (tag.isBlank()) {
            return;
        }

        try {
            String key = PREFIX + userId;
            UserInterestTag existing = readTag(key, tag);

            UserInterestTag updated = (existing == null)
                    ? UserInterestTag.initial(tag)
                    : existing.hit(props.getWeightIncrement(), props.getMaxWeight());

            String json = objectMapper.writeValueAsString(updated);
            redis.opsForHash().put(key, tag, json);
            redis.expire(key, Duration.ofDays(props.getTtlDays()));

            // 容量控制——超出上限时淘汰权重最低的
            trimIfNeeded(key);

            log.info("兴趣标签记录: userId={}, tag={}, weight={}",
                    userId, tag, String.format("%.2f", updated.weight()));

        } catch (Exception e) {
            log.warn("记录兴趣标签失败: userId={}, tag={}", userId, tag, e);
        }
    }

    // ==================== 读取 ====================

    /**
     * 读 TopN 标签（按权重降序）
     */
    public List<UserInterestTag> topTags(String userId, int n) {
        if (!props.isEnabled() || userId == null) {
            return List.of();
        }

        try {
            Map<Object, Object> raw = redis.opsForHash().entries(PREFIX + userId);
            if (raw.isEmpty()) {
                return List.of();
            }

            return raw.values().stream()
                    .map(v -> {
                        try {
                            return objectMapper.readValue(v.toString(), UserInterestTag.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .filter(t -> t.weight() >= props.getMinWeight())
                    .sorted(Comparator.comparingDouble(UserInterestTag::weight).reversed())
                    .limit(n)
                    .toList();

        } catch (Exception e) {
            log.warn("读取兴趣标签失败: userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 渲染成 SystemMessage 片段
     * <p>
     * 空标签 → 返回空字符串——调用方判断后跳过注入。
     */
    public String renderAsSystemText(String userId) {
        List<UserInterestTag> tags = topTags(userId, props.getInjectTopN());
        if (tags.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("用户长期关注的技术领域（按关注度排序）：\n");
        for (UserInterestTag t : tags) {
            sb.append("- ").append(t.tag())
                    .append("（关注度 ").append(String.format("%.1f", t.weight() * 100)).append("%）\n");
        }
        return sb.toString();
    }

    // ==================== 内部 ====================

    private UserInterestTag readTag(String key, String tag) {
        Object v = redis.opsForHash().get(key, tag);
        if (v == null) {
            return null;
        }
        try {
            return objectMapper.readValue(v.toString(), UserInterestTag.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 容量控制——标签数超过上限时，删掉权重最低的
     */
    private void trimIfNeeded(String key) {
        Long size = redis.opsForHash().size(key);
        if (size == null || size <= props.getMaxTagsPerUser()) {
            return;
        }

        try {
            Map<Object, Object> all = redis.opsForHash().entries(key);
            // 找权重最低的淘汰
            all.entrySet().stream()
                    .map(e -> {
                        try {
                            UserInterestTag t = objectMapper.readValue(
                                    e.getValue().toString(), UserInterestTag.class);
                            return Map.entry(e.getKey(), t.weight());
                        } catch (Exception ex) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.comparingDouble(Map.Entry::getValue))
                    .ifPresent(e -> {
                        redis.opsForHash().delete(key, e.getKey());
                        log.info("兴趣标签淘汰: key={}, tag={}", key, e.getKey());
                    });
        } catch (Exception e) {
            log.warn("标签淘汰失败: key={}", key, e);
        }
    }

    /**
     * 标签规范化——去空格、限长、脱敏
     */
    private String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() > 32) {
            s = s.substring(0, 32);
        }
        return SensitiveDataMasker.mask(s);
    }
}