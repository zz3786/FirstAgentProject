package org.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.utils.SensitiveDataMasker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LongTermMemoryService {

    private static final String PREFIX = "LTM:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LongTermMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 写入一条长期记忆 */
    public void save(String userId, String type, String content) {
        try {
            content = SensitiveDataMasker.mask(content);   // ← 存之前脱敏
            String id = System.currentTimeMillis() + "_" + Math.random();
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", type,
                    "content", content,
                    "time", LocalDate.now().toString()
            ));
            redis.opsForHash().put(PREFIX + userId, id, json);
            redis.expire(PREFIX + userId, Duration.ofDays(180));
        } catch (Exception e) {
            throw new RuntimeException("保存长期记忆失败", e);
        }
    }

    /** 基于当前问题检索相关记忆片段 */
    public List<String> search(String userId, String query, int topK) {
        Map<Object, Object> all = redis.opsForHash().entries(PREFIX + userId);
        if (all.isEmpty()) {
            return List.of();
        }

        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }

        return all.values().stream()
                .map(Object::toString)
                .filter(json -> containsAny(json, keywords))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /** 提取关键词：英文按空格切，中文按 2-gram 切 */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new java.util.ArrayList<>();

        // 1. 英文/数字：按非字母数字切分
        for (String token : query.split("[^\\u4e00-\\u9fa5a-zA-Z0-9]+")) {
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }

        // 2. 中文：滑动窗口取相邻 2 字
        for (int i = 0; i < query.length() - 1; i++) {
            char c1 = query.charAt(i);
            char c2 = query.charAt(i + 1);
            if (isChinese(c1) && isChinese(c2)) {
                keywords.add("" + c1 + c2);
            }
        }

        return keywords;
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    /** 只要记忆 JSON 里包含任一关键词，就算命中 */
    private boolean containsAny(String json, List<String> keywords) {
        return keywords.stream().anyMatch(json::contains);
    }
}