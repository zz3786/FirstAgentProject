package org.example.cache.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;   // ★ 关键 import
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

@Slf4j
@Configuration
public class SemanticCacheConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean("cacheVectorStore")
    public VectorStore cacheVectorStore(EmbeddingModel embeddingModel,
                                        SemanticCacheProperties props) {
        log.info("初始化语义缓存 VectorStore：host={}, port={}, index={}",
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
                        MetadataField.tag("tenant_id"),      // 精确匹配（过滤用）
                        MetadataField.text("question"),      // ★ 全文检索字段
                        MetadataField.text("answer")         // ★ 全文检索字段
                )
                .initializeSchema(true)
                .build();
    }
}