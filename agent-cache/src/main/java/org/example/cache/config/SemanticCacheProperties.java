package org.example.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.cache.semantic")
public class SemanticCacheProperties {

    /** 是否启用语义缓存 */
    private boolean enabled = true;

    /** Redis 向量索引名 */
    private String indexName = "semantic-cache-index";

    /** Redis key 前缀 */
    private String keyPrefix = "semantic-cache:";

    /** 相似度阈值（0~1，越大越严格，建议 0.85） */
    private double similarityThreshold = 0.85;

    /** 缓存 TTL（小时） */
    private long ttlHours = 24;

    /** 每个会话最多缓存条数（防膨胀） */
    private int maxCachePerUser = 200;
}