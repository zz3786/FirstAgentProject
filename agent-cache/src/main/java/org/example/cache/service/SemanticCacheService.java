package org.example.cache.service;

import lombok.extern.slf4j.Slf4j;
import org.example.cache.config.SemanticCacheProperties;
import org.example.common.utils.RedisTagUtils;
import org.example.common.utils.TextUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 语义缓存服务（字段拆分版）
 * <p>
 * <b>核心改动</b>：不再用 {@code "user-alice::财务部,公开|-|||3|active"} 这种
 * 拼接字符串做缓存 key——改为"每个维度存一个独立的 metadata 字段"。
 * <p>
 * <b>解决的问题</b>：
 * <ul>
 *   <li>特殊字符（{@code - : | ,}）导致的 RediSearch 语法错误</li>
 *   <li>不可读、不可独立查询</li>
 *   <li>顺序敏感（集合顺序不同生成不同 key）</li>
 * </ul>
 * <p>
 * <b>哨兵值</b>：每个维度都显式存——缺失用 {@code "__NONE__"} 表示，
 * 避免"空 filter"查询误命中"有 filter"的缓存。
 */
@Slf4j
@Service
public class SemanticCacheService {

    private static final String NONE = "__NONE__";

    private final VectorStore cacheVectorStore;
    private final SemanticCacheProperties properties;

    public SemanticCacheService(@Qualifier("cacheVectorStore") VectorStore cacheVectorStore,
                                SemanticCacheProperties properties) {
        this.cacheVectorStore = cacheVectorStore;
        this.properties = properties;
    }

    // ==================== 查缓存 ====================

    /**
     * 查缓存
     *
     * @param query      用户问题
     * @param userId     用户 ID（纯 userId，不含 sessionTag）
     * @param filterDims 过滤维度 Map——key 见 {@link #buildFilterExpression}
     * @return 命中的答案；未命中返回 null
     */
    public String lookup(String query, String userId, Map<String, Object> filterDims) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (query == null || query.isBlank()) {
            return null;
        }
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }

        try {
            Filter.Expression expr = buildFilterExpression(userId, filterDims);

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(1)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression(expr)
                    .build();

            List<Document> results = cacheVectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                log.debug("缓存未命中: userId=[{}], query=[{}]", RedisTagUtils.escape(userId), TextUtils.truncate(query,30));
                return null;
            }

            Document hit = results.get(0);
            Object answerObj = hit.getMetadata().get("answer");
            if (answerObj == null) {
                log.warn("缓存命中但 answer 为 null——metadataFields 未声明或索引未重建");
                return null;
            }

            Object cachedQuestion = hit.getMetadata().get("question");
            log.info("✅ 语义缓存命中: userId=[{}], query=[{}] ≈ 缓存问题=[{}]",
                    RedisTagUtils.escape(userId), TextUtils.truncate(query,30),
                    TextUtils.truncate(cachedQuestion == null ? "" : cachedQuestion.toString(),30));
            return answerObj.toString();

        } catch (Exception e) {
            log.warn("查缓存失败，降级为未命中", e);
            return null;
        }
    }

    /** 兼容——无过滤维度 */
    public String lookup(String query, String userId) {
        return lookup(query, userId, Map.of());
    }

    // ==================== 存缓存 ====================

    /**
     * 存缓存
     * <p>
     * 每个维度都以 {@code normalizeList} / {@code normalizeScalar} 处理——
     * 缺失用哨兵值，集合排序——保证同一语义生成同一条缓存。
     */
    public void store(String query, String answer, String userId, Map<String, Object> filterDims) {
        if (!properties.isEnabled()) {
            return;
        }
        if (query == null || query.isBlank()) {
            return;
        }
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }

        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("question", query);
            meta.put("answer", answer);
            meta.put("user_id", RedisTagUtils.escape(userId));

            // ★ 每个维度都显式存——缺失用哨兵
            meta.put("departments", normalizeList(filterDims.get("departments")));
            meta.put("year_from", normalizeScalar(filterDims.get("year_from")));
            meta.put("year_to", normalizeScalar(filterDims.get("year_to")));
            meta.put("doc_types", normalizeList(filterDims.get("doc_types")));
            meta.put("security_level", normalizeScalar(filterDims.get("security_level")));
            meta.put("statuses", normalizeList(filterDims.get("statuses")));

            Document doc = new Document(query, meta);
            cacheVectorStore.add(List.of(doc));
            log.info("缓存已存: userId=[{}], query=[{}], depts={}, sec={}, status={}",
                    userId, TextUtils.truncate(query,30),
                    meta.get("departments"), meta.get("security_level"), meta.get("statuses"));

        } catch (Exception e) {
            log.warn("存缓存失败", e);
        }
    }

    /** 兼容——无过滤维度 */
    public void store(String query, String answer, String userId) {
        store(query, answer, userId, Map.of());
    }

    // ==================== 过滤表达式构造 ====================

    /**
     * 构造 RediSearch 过滤表达式
     * <p>
     * 每个维度独立 AND——{@code user_id == 'xxx' && departments in [...] && security_level == '3'}
     * <p>
     * <b>为什么全部维度都必须写</b>：
     * 如果不写某维度，RediSearch 不约束它——空 filter 的查询会误命中"有 filter"的缓存。
     * 用哨兵值 {@code "__NONE__"} 让"缺失"成为一个显式的值。
     */
    private Filter.Expression buildFilterExpression(String userId, Map<String, Object> dims) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> ops = new ArrayList<>();

        ops.add(b.eq("user_id", RedisTagUtils.escape(userId)));

        List<String> deps = normalizeList(dims.get("departments"));
        ops.add(b.in("departments", deps.toArray()));

        ops.add(b.eq("year_from", normalizeScalar(dims.get("year_from"))));
        ops.add(b.eq("year_to", normalizeScalar(dims.get("year_to"))));

        List<String> types = normalizeList(dims.get("doc_types"));
        ops.add(b.in("doc_types", types.toArray()));

        ops.add(b.eq("security_level", normalizeScalar(dims.get("security_level"))));

        List<String> statuses = normalizeList(dims.get("statuses"));
        ops.add(b.in("statuses", statuses.toArray()));

        FilterExpressionBuilder.Op result = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            result = b.and(result, ops.get(i));
        }
        return result.build();
    }

    // ==================== 归一化 ====================

    /**
     * 列表归一化——null/空 → [__NONE__]；有值 → 排序后的字符串列表
     * <p>
     * <b>为什么排序</b>：{@code ["财务部","公开"]} 和 {@code ["公开","财务部"]}
     * 必须生成同一条缓存——排序消除顺序差异。
     */
    @SuppressWarnings("unchecked")
    private List<String> normalizeList(Object obj) {
        if (!(obj instanceof List<?> list) || list.isEmpty()) {
            return List.of(NONE);
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .sorted()
                .toList();
    }

    /**
     * 标量归一化——null → __NONE__；其他 → toString
     */
    private String normalizeScalar(Object obj) {
        return obj == null ? NONE : obj.toString();
    }

    // ==================== 缓存清理 ====================

    public void clearAll() {
        try {
            // ★ 用 RedisVectorStore 的 delete——传一个恒真条件  但这是依赖 RediSearch 对 != 的行为 有的版本不知道，根据实际情况决定
            cacheVectorStore.delete("user_id != '__IMPOSSIBLE__'");
            log.info("✅ 已清除全部语义缓存");
        } catch (Exception e) {
            log.error("清全部缓存失败", e);
        }
    }

    public void clearByUser(String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        try {
            cacheVectorStore.delete("user_id == '" + RedisTagUtils.escape(userId) + "'");
            log.info("已清除用户 {} 的语义缓存", userId);
        } catch (Exception e) {
            log.error("清用户缓存失败: userId={}", userId, e);
        }
    }

}