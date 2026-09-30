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
 * 语义缓存服务（多租户 + 过滤感知版）
 * <p>
 * <b>D46 核心设计：filter 纳入缓存 key</b>
 * <p>
 * 原来的 key 只有 (query, tenantId)——但同一租户下，
 * "部门=财务部"和"部门=研发部"问同一个问题，答案完全不同。
 * 不把 filter 纳入 key，会命中错误的缓存——比不用缓存更糟。
 * <p>
 * <b>为什么参数是 String 而不是 RagFilter</b>：
 * 本类在 agent-cache 模块，RagFilter 在 agent-rag 模块——
 * 引入 RagFilter 会造成循环依赖。
 * 由调用方传"已拼好的 key 后缀"——本类只做字符串拼接，不感知过滤语义。
 * <p>
 * <b>复合 key 格式</b>：
 * <pre>
 * {tenantId}::{cacheKeySuffix}
 * </pre>
 * 例：
 * <pre>
 * user-alice::                                              ← 无过滤
 * user-alice::财务部|-|||2|active                           ← 部门+密级2+仅active
 * user-alice::财务部,人事部|2024-2026|制度|||active         ← 多维度组合
 * </pre>
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

    // ==================== 查缓存 ====================

    /**
     * 查缓存（带租户 + 过滤隔离）
     *
     * @param query          用户问题
     * @param tenantId       租户/用户 ID（纯 userId，不含 sessionTag）
     * @param cacheKeySuffix 过滤条件后缀（由 filter.cacheKeySuffix() 生成；null 或 "" 表示无过滤）
     * @return 命中的答案；未命中返回 null
     */
    public String lookup(String query, String tenantId, String cacheKeySuffix) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (query == null || query.isBlank()) {
            return null;
        }
        if (tenantId == null) {
            tenantId = "default";
        }

        String cacheKey = buildCacheKey(tenantId, cacheKeySuffix);

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(1)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression("tenant_id == '" + cacheKey + "'")   // ★ 复合 key 过滤
                    .build();

            List<Document> results = cacheVectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                log.debug("缓存未命中: query=[{}], cacheKey=[{}]", truncate(query), cacheKey);
                return null;
            }

            Document hit = results.get(0);

            Object answerObj = hit.getMetadata().get("answer");
            if (answerObj == null) {
                log.warn("缓存命中但 answer 为 null——metadataFields 未声明或索引未重建");
                return null;
            }

            Object cachedQuestion = hit.getMetadata().get("question");
            log.info("✅ 语义缓存命中: cacheKey=[{}], query=[{}] ≈ 缓存问题=[{}]",
                    cacheKey, truncate(query),
                    truncate(cachedQuestion == null ? "" : cachedQuestion.toString()));
            return answerObj.toString();

        } catch (Exception e) {
            log.warn("查缓存失败，降级为未命中", e);
            return null;
        }
    }

    /** 兼容旧调用——无过滤 */
    public String lookup(String query, String tenantId) {
        return lookup(query, tenantId, null);
    }

    // ==================== 存缓存 ====================

    /**
     * 存缓存（带租户 + 过滤隔离）
     */
    public void store(String query, String answer, String tenantId, String cacheKeySuffix) {
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

        String cacheKey = buildCacheKey(tenantId, cacheKeySuffix);

        try {
            Document doc = new Document(
                    query,                                              // Document 内容是 question
                    Map.of(
                            "question", query,
                            "answer", answer,
                            "tenant_id", cacheKey,                       // ★ 存复合 key
                            "raw_tenant_id", tenantId,                   // ★ 保留原始 tenantId（便于统计）
                            "filter_suffix", cacheKeySuffix == null ? "" : cacheKeySuffix  // ★ 便于调试
                    )
            );
            cacheVectorStore.add(List.of(doc));
            log.info("缓存已存: cacheKey=[{}], query=[{}]", cacheKey, truncate(query));

        } catch (Exception e) {
            log.warn("存缓存失败", e);
        }
    }

    /** 兼容旧调用——无过滤 */
    public void store(String query, String answer, String tenantId) {
        store(query, answer, tenantId, null);
    }

    // ==================== 复合 key 构造 ====================

    /**
     * 构造缓存 key
     * <p>
     * 格式：{tenantId}::{cacheKeySuffix}
     * <p>
     * 无过滤时 suffix 为空——key 变成 "user-alice::"
     * （仍然用 "::" 分隔——与旧版本的 "user-alice" 不兼容，
     * 但旧缓存会被清空重建，所以无影响）
     */
    private String buildCacheKey(String tenantId, String cacheKeySuffix) {
        if (cacheKeySuffix == null || cacheKeySuffix.isBlank()) {
            return tenantId + "::";
        }
        return tenantId + "::" + cacheKeySuffix;
    }

    // ==================== 缓存清理 ====================

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

    public void clearByTenant(String tenantId) {
        if (tenantId == null) {
            tenantId = "default";
        }
        try {
            // ★ 用前缀匹配——清掉该租户的所有 filter 变体
            //   注意：RedisVectorStore 的 delete 用 LIKE 需要 tenant_id 声明为 TEXT 类型，
            //   若是 TAG 类型则用 ==。你当前是 TAG——改用精确匹配所有变体较复杂，
            //   暂时仅清"无过滤"版本的缓存；如需彻底清，用 clearAll()。
            cacheVectorStore.delete("tenant_id == '" + tenantId + "::'");
            log.info("已清除租户 {} 的语义缓存（无过滤版本）", tenantId);
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