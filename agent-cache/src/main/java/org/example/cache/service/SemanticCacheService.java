package org.example.cache.service;

import lombok.extern.slf4j.Slf4j;
import org.example.cache.config.SemanticCacheProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义缓存服务（多租户版）
 * <p>
 * 用向量相似度判断"是否问过类似的问题"，按 tenantId 隔离。
 */
@Slf4j
@Service
public class SemanticCacheService {

    private final VectorStore cacheVectorStore;
    private final SemanticCacheProperties properties;
    private final StringRedisTemplate redis;

    public SemanticCacheService(@Qualifier("cacheVectorStore") VectorStore cacheVectorStore,
                                SemanticCacheProperties properties,
                                StringRedisTemplate redis) {
        this.cacheVectorStore = cacheVectorStore;
        this.properties = properties;
        this.redis = redis;
    }

    /**
     * 查缓存（带租户隔离）
     *
     * @param query    用户问题
     * @param tenantId 租户/用户 ID
     * @return 命中的答案；未命中返回 null
     */
    public String lookup(String query, String tenantId) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (query == null || query.isBlank()) {
            return null;
        }
        if (tenantId == null) {
            tenantId = "default";
        }

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(1)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression("tenant_id == '" + tenantId + "'")
                    .build();

            //similaritySearch 是黑盒——你传文本，它内部自动 embed。
            /*
             * 一句话总结
             * 是的，两件事都干了：
             * 问题转向量（调阿里云）
             * Redis 相似度检索
             * 你只写一行 similaritySearch(request) —— 框架内部自动完成。
             * RAG 和缓存用同一个 Embedding 模型——每次检索都要重新 embed。
             */
            List<Document> results = cacheVectorStore.similaritySearch(request);

            if (results == null || results.isEmpty()) {
                log.debug("缓存未命中: query=[{}], tenant={}", truncate(query), tenantId);
                return null;
            }

            Document hit = results.get(0);
            String answer = hit.getText();
            Object cachedQuestion = hit.getMetadata().get("question");
            if (answer == null) {
                return null;
            }

            log.info("✅ 语义缓存命中: tenant={}, query=[{}] ≈ 缓存问题=[{}]",
                    tenantId, truncate(query),
                    truncate(cachedQuestion == null ? "" : cachedQuestion.toString()));
            return answer.toString();

        } catch (Exception e) {
            log.warn("查缓存失败，降级为未命中", e);
            return null;
        }
    }

    /**
     * 存缓存（带租户）
     */
    public void store(String query, String answer, String tenantId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (query == null || query.isBlank()) {
            return;
        }
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (tenantId == null) {
            tenantId = "default";
        }

        try {
            Document doc = new Document(answer, Map.of(
                    "question", query,
                    "answer", answer,
                    "tenant_id", tenantId
            ));
            cacheVectorStore.add(List.of(doc));
            log.debug("缓存已存: tenant={}, query=[{}]", tenantId, truncate(query));
        } catch (Exception e) {
            log.warn("存缓存失败", e);
        }
    }

    /**
     * 清空所有缓存（文档更新时调用）
     */
    public void clearAll() {
        try {
            Set<String> keys = redis.keys(properties.getKeyPrefix() + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("✅ 已清除 {} 个语义缓存 key", keys.size());
            } else {
                log.info("无需清除缓存（无数据）");
            }
        } catch (Exception e) {
            log.error("清全部缓存失败", e);
        }
    }

    /**
     * 清空指定租户的缓存
     * <p>
     * TODO：当前未使用——将来以下场景可启用：
     * - 用户主动"清空我的缓存"
     * - 用户权限变更
     * - 租户删除
     */
    public void clearByTenant(String tenantId) {
        if (tenantId == null) {
            tenantId = "default";
        }
        try {
            cacheVectorStore.delete("tenant_id == '" + tenantId + "'");
            log.info("已清除租户 {} 的语义缓存", tenantId);
        } catch (Exception e) {
            log.error("清租户缓存失败: tenantId={}", tenantId, e);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}