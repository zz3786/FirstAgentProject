package org.example.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 最近记忆,不是指时间上的最近 是指顺序上最近
 */
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String PREFIX = "CHAT:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redis,ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream().map(k -> k.substring(PREFIX.length())).collect(Collectors.toList());
    }

    /**
     * 你的业务代码
     *     ↓
     * chatMemory.get(conversationId)          ← 你调用 ChatMemory 接口
     *     ↓
     * MessageWindowChatMemory.get(...)        ← Spring AI 的实现
     *     ↓
     * chatMemoryRepository.findByConversationId(...)   ← 内部调用 Repository
     *     ↓
     * RedisChatMemoryRepository.findByConversationId(...)   ← 你的实现
     *     ↓
     * redis.opsForList().range(...)           ← 读 Redis
     *
     * 你写的方法是被 MessageWindowChatMemory 调用的，不是被你自己调用的。
     * @param conversationId
     * @return
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> jsons = redis.opsForList().range(PREFIX + conversationId, 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return List.of();
        }
        return jsons.stream().map(json -> {
            try {
                MessageDto dto = objectMapper.readValue(json, MessageDto.class);
                return dto.toMessage();
            } catch (Exception e) {
                throw new RuntimeException("反序列化消息失败: " + json, e);
            }
        }).collect(Collectors.toList());
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
}