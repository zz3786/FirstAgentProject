package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * D50 对话历史向量库装配
 * <p>
 * <b>为什么独立建索引而不复用 semantic-cache</b>：
 * <ul>
 *   <li>语义缓存是 query → answer 直返，命中就短路 LLM</li>
 *   <li>对话历史是 query → 相关轮次注入 Prompt，仍走 LLM</li>
 *   <li>两者生命周期、TTL、清理策略完全不同——混用必然互相污染</li>
 * </ul>
 * <p>
 * <b>为什么 user_id 用 TAG 而不是 TEXT</b>：
 * TAG 走倒排索引，精确匹配 O(1)；
 * TEXT 会变成全文分词匹配——性能骤降 + 跨租户泄漏风险。
 */
@Slf4j
@Configuration
public class ConversationVectorStoreConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean("conversationVectorStore")
    public VectorStore conversationVectorStore(
            EmbeddingModel embeddingModel,
            ConversationMemoryProperties props) {

        log.info("初始化对话历史 VectorStore：host={}, port={}, index={}",
                redisHost, redisPort, props.getIndexName());

        JedisPooled jedis;
        if (redisPassword != null && !redisPassword.isBlank()) {
            jedis = new JedisPooled(
                    new HostAndPort(redisHost, redisPort),
                    DefaultJedisClientConfig.builder()
                            .password(redisPassword)
                            .build()
            );
        } else {
            jedis = new JedisPooled(redisHost, redisPort);
        }

        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(props.getIndexName())
                .prefix(props.getKeyPrefix())
                // ★★★ 关键：声明所有用于过滤的元数据字段
                .metadataFields(
                        MetadataField.tag("user_id"),            // 租户隔离（必须 TAG）
                        MetadataField.tag("conversation_id"),    // 会话隔离
                        MetadataField.text("user_message"),      // 全文兜底
                        MetadataField.text("assistant_message")
                )
                .initializeSchema(true)
                .build();
    }
}