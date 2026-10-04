package org.example.rag.retrieval.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.retrieval.config.RetrievalProperties;
import org.example.rag.shared.model.RagFilter;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════
 * 混合检索融合策略速查
 * ═══════════════════════════════════════════════════════════
 *
 * 【RRF 融合】
 * 公式：score(d) = Σ 1 / (k + rank_i(d)),  k=60
 * 特点：只看排名，不看分数；两路等权
 * 适用：两路分数尺度差异大、不可比
 *
 * 【加权融合】
 * 公式：score(d) = w_vec × norm(vec_score) + w_kw × norm(kw_score)
 * 特点：分数归一化后可调权重
 * 适用：知道哪一路更可信、需要精细控制
 *
 * 【归一化公式】
 * norm = (value - min) / (max - min)
 * 反转：1.0 - norm（当原分数"越小越好"时）
 *
 * 【去重 Key】
 * docId + "_" + chunkIndex
 * 同一个 chunk 可能被两路同时召回 —— 用 key 去重、分数累加
 *
 * ═══════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final KeywordSearchService keywordSearchService;
    private final RemoteRerankService remoteRerankService;
    private final RetrievalProperties retrievalProperties;

    public HybridSearchService(VectorStore vectorStore,
                               KeywordSearchService keywordSearchService,
                               RemoteRerankService remoteRerankService, 
                               RetrievalProperties retrievalProperties) {
        this.vectorStore = vectorStore;
        this.keywordSearchService = keywordSearchService;
        this.remoteRerankService = remoteRerankService;
        this.retrievalProperties = retrievalProperties;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    // ==================== 三个入口 ====================

    /**
     * 混合检索（主入口）——无过滤、无画像
     * <p>
     * 最简调用——委托给三参版本，传入 empty 画像。
     */
    public List<Document> search(String query) {
        return search(query, null, RetrievalProfile.empty());
    }

    /**
     * 混合检索（主入口）——D46 支持元数据过滤，无个性化
     * <p>
     * 兼容旧调用——委托给三参版本，传入 empty 画像。
     *
     * @param query  用户问题
     * @param filter 过滤条件（null 或 empty 表示不过滤）
     */
    public List<Document> search(String query, RagFilter filter) {
        return search(query, filter, RetrievalProfile.empty());
    }

    /**
     * 混合检索主入口（带个性化画像）
     * @param query
     * @param filter
     * @param profile
     * @return
     */
    public List<Document> search(String query, RagFilter filter, RetrievalProfile profile) {
        return search(query, filter, profile, retrievalProperties.getTopK());
    }

    /**
     * 混合检索主入口（带个性化画像）+topK 用于推荐场景——需要比回答时更大的召回量
     * <p>
     * ★ D49：权重倾斜 + 画像加分都在这条路径上生效。
     *
     * @param query   用户问题
     * @param filter  过滤条件（硬权限 + 软筛选）
     * @param profile 检索画像（空画像退化为全局默认权重）
     */
    public List<Document> search(String query, RagFilter filter, RetrievalProfile profile, int topK) {
        StopWatch sw = new StopWatch("RAG检索");

        int recallSize = topK * retrievalProperties.getRecallMultiplier();

        // ① 向量检索（带过滤）
        sw.start("向量检索");
        List<Document> vectorResults = vectorSearch(query, recallSize, filter);
        sw.stop();

        // ② 关键词检索（带过滤）
        sw.start("关键词检索");
        List<Document> keywordResults = keywordSearchService.search(query, recallSize, filter);
        sw.stop();

        log.info("混合检索：向量 {} 条，关键词 {} 条，过滤={}，画像={}，topK={}",
                vectorResults.size(), keywordResults.size(),
                filter == null || filter.isEmpty() ? "无" : filter,
                profile == null || profile.isEmpty() ? "无" : profile,
                topK);

        // ③ 融合
        sw.start("融合");
        List<Document> fused;
        if ("weighted".equalsIgnoreCase(retrievalProperties.getFusionStrategy())) {
            fused = weightedFuse(vectorResults, keywordResults, topK, profile);
        } else {
            fused = rrfFuse(vectorResults, keywordResults, topK);
        }
        sw.stop();

        // ④ TEI 精排
        sw.start("TEI精排");
        List<Document> reranked = remoteRerankService.rerank(query, fused, topK);
        sw.stop();

        log.info("RAG检索完成 query=[{}] 向量={} 关键词={} 融合={} 精排={} 耗时={}ms",
                truncate(query, 30),
                vectorResults.size(), keywordResults.size(),
                fused.size(), reranked.size(),
                sw.getTotalTimeMillis());

        return reranked;
    }

    // ==================== 向量检索 ====================

    /**
     * 向量检索（带过滤）
     * <p>
     * ★ D46 关键改动：SearchRequest.builder() 增加 filterExpression(...)
     * <p>
     * <b>为什么过滤放在向量检索阶段而非检索后</b>：
     * Qdrant 在 HNSW 图遍历时就能跳过不匹配的点，
     * 不需要先召回 Top-K 再丢弃——这在高并发下节省大量计算。
     * <p>
     * <b>执行流程</b>：
     * <pre>
     * ① 提取 query 字符串（如"家庭医生签约注意事项"）
     * ② 调用 EmbeddingModel.embed(query)     ← 框架自动把问题转向量
     * ③ 拿这个向量去 Qdrant 检索
     *    POST /collections/agent-rag/points/search
     *    body: { "vector": [0.12, -0.34, ...], "limit": 10, "filter": {...} }
     * ④ Qdrant 返回 Top-K 相似片段
     * ⑤ 包装成 List&lt;Document&gt; 返回
     * </pre>
     */
    private List<Document> vectorSearch(String query, int topK, RagFilter filter) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);

            if (retrievalProperties.getSimilarityThreshold() > 0) {
                builder.similarityThreshold(retrievalProperties.getSimilarityThreshold());
            }

            // 施加过滤条件
            if (filter != null && !filter.isEmpty()) {
                Filter.Expression expr = filter.toExpression();
                if (expr != null) {
                    builder.filterExpression(expr);
                }
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            return results != null ? results : List.of();

        } catch (Exception e) {
            log.error("向量检索失败, query={}", query, e);
            return List.of();
        }
    }

    // ==================== RRF 融合 ====================

    /**
     * RRF 融合算法
     * <p>
     * 公式：score(d) = Σ 1 / (k + rank_i(d))
     * <p>
     * 优点：
     * - 不需要为向量分数和关键词分数做归一化
     * - 只看排名，对分数尺度不敏感
     * - 简单、稳定、效果好
     */
    private List<Document> rrfFuse(List<Document> vectorResults,
                                   List<Document> keywordResults,
                                   int topK) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // 向量检索的 RRF 贡献
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            Document doc = vectorResults.get(rank);
            String key = buildKey(doc);
            double rrfScore = 1.0 / (retrievalProperties.getRrfK() + rank + 1);
            rrfScores.merge(key, rrfScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        }

        // 关键词检索的 RRF 贡献
        for (int rank = 0; rank < keywordResults.size(); rank++) {
            Document doc = keywordResults.get(rank);
            String key = buildKey(doc);
            double rrfScore = 1.0 / (retrievalProperties.getRrfK() + rank + 1);
            rrfScores.merge(key, rrfScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        }

        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    doc.getMetadata().put("rrf_score", e.getValue());
                    return doc;
                })
                .toList();
    }

    /**
     * 构造去重 key：docId + chunkIndex
     * <p>
     * 同一个 chunk 可能同时被两路召回——用这个 key 去重。
     */
    private String buildKey(Document doc) {
        Object docId = doc.getMetadata().get("doc_id");
        Object chunkIdx = doc.getMetadata().get("chunk_index");
        return docId + "_" + chunkIdx;
    }

    // ==================== 加权融合 ====================

    /**
     * 加权融合（归一化线性加权 + 个性化画像）
     * <p>
     * ═══════════════════════════════════════════════════════════════
     * 【为什么需要这个方法】
     * ═══════════════════════════════════════════════════════════════
     * 向量检索和关键词检索的分数尺度完全不同：
     * - 向量路：返回 distance（0~1），越小越相关
     * - 关键词路：返回 score（0~10+），越大越相关
     * 两者无法直接相加（0.15 + 3.28 没有意义），必须先统一到：
     * - 同一尺度：[0, 1]
     * - 同一方向：都是"越大越好"
     * <p>
     * ═══════════════════════════════════════════════════════════════
     * 【核心步骤】
     * ═══════════════════════════════════════════════════════════════
     * ① 归一化向量分数 → [0,1]，并反转（distance 越小越好 → 反转后越大越好）
     * ② 归一化关键词分数 → [0,1]（score 越大越好，无需反转）
     * ③ 权重计算——叠加画像偏移
     * ④ 加权求和：fused = wVec × norm_vec + wKw × norm_kw
     * ⑤ 画像加分——对符合偏好的文档 × boost
     * ⑥ 降序排序取 Top-K
     *
     * @param vectorResults  向量检索结果（metadata 里含 "distance" 字段）
     * @param keywordResults 关键词检索结果（metadata 里含 "score" 字段）
     * @param topK           最终返回条数
     * @param profile        检索画像（null 或空画像 = 无个性化）
     */
    private List<Document> weightedFuse(List<Document> vectorResults,
                                        List<Document> keywordResults,
                                        int topK,
                                        RetrievalProfile profile) {

        // ① 归一化
        Map<String, Double> vectorScores = normalize(vectorResults, "distance", true);
        Map<String, Double> keywordScores = normalize(keywordResults, "score", false);

        // ② 权重计算——叠加画像偏移
        double baseVec = retrievalProperties.getVectorWeight();
        double baseKw = retrievalProperties.getKeywordWeight();

        // ★ 应用画像偏移——只调 w_vec，w_kw = 1 - w_vec
        double wVec = (profile == null || profile.isEmpty())
                ? baseVec
                : profile.applyWeightBias(baseVec);
        double wKw = 1.0 - wVec;

        log.info("融合权重：base=({}, {}), adjusted=({}, {}), profile={}",
                baseVec, baseKw,
                String.format("%.2f", wVec), String.format("%.2f", wKw),
                profile == null ? "null" : profile);

        // ③ 合并
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        vectorResults.forEach(doc -> {
            String key = buildKey(doc);
            double normScore = vectorScores.getOrDefault(key, 0.0);
            fusedScores.merge(key, wVec * normScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        });

        keywordResults.forEach(doc -> {
            String key = buildKey(doc);
            double normScore = keywordScores.getOrDefault(key, 0.0);
            fusedScores.merge(key, wKw * normScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        });

        // ④ ★ 画像加分
        applyProfileBoost(fusedScores, docMap, profile);

        // ⑤ 排序取 Top-K
        return fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    doc.getMetadata().put("fused_score", e.getValue());
                    return doc;
                })
                .toList();
    }

    /**
     * 画像加分——对符合偏好的文档乘以系数
     * <p>
     * <b>为什么是"加分"而非"硬过滤"</b>：
     * 硬过滤会把不符合偏好的相关文档直接排除——降低召回率。
     * 加分是"排序上优先"——用户仍能看到全部结果，只是排在最前的更符合偏好。
     * <p>
     * <b>为什么加分系数有上限（1.20）</b>：
     * 防止"用户问过财务部，永远只看到财务部"——20% 的加权足以改变排序，
     * 但不足以完全屏蔽其他部门。
     */
    private void applyProfileBoost(Map<String, Double> fusedScores,
                                   Map<String, Document> docMap,
                                   RetrievalProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return;
        }

        double boost = profile.filterBoost();
        if (boost <= 1.0) {
            return;
        }

        int boostedCount = 0;
        for (Map.Entry<String, Double> entry : fusedScores.entrySet()) {
            Document doc = docMap.get(entry.getKey());
            if (doc == null) continue;

            boolean matched = false;

            // 部门匹配
            if (profile.hasPreferredDepartments()) {
                Object dept = doc.getMetadata().get("department");
                if (dept != null && profile.preferredDepartments().contains(dept.toString())) {
                    matched = true;
                }
            }

            // 内容类型匹配
            if (!matched && !profile.preferredDocTypes().isEmpty()) {
                Object type = doc.getMetadata().get("content_type");
                if (type != null && profile.preferredDocTypes().contains(type.toString())) {
                    matched = true;
                }
            }

            if (matched) {
                entry.setValue(entry.getValue() * boost);
                boostedCount++;
            }
        }

        if (boostedCount > 0) {
            log.info("画像加分：{} 条文档 × {}", boostedCount, boost);
        }
    }

    // ==================== 归一化 ====================

    /**
     * Min-Max 归一化：把一组分数映射到 [0, 1]
     * <p>
     * ═══════════════════════════════════════════════════════════════
     * 【公式】
     * ═══════════════════════════════════════════════════════════════
     * norm = (value - min) / (max - min)
     * <p>
     * ═══════════════════════════════════════════════════════════════
     * 【reverse 参数的作用】
     * ═══════════════════════════════════════════════════════════════
     * 有些分数"越小越好"（如 distance），有些"越大越好"（如 score）。
     * 为了让所有分数方向统一（都是"越大越好"），
     * 当原分数越小越好时，传 reverse=true，用 (1 - norm) 反转。
     * <p>
     * 【举例】（reverse=true）
     * 原始 distance:  0.15, 0.35, 0.80
     * 归一化后:        0.00, 0.31, 1.00
     * 反转后:          1.00, 0.69, 0.00   ← 现在"越大越好"
     *
     * @param docs       文档列表（metadata 里含 scoreField）
     * @param scoreField metadata 中的分数字段名（"distance" 或 "score"）
     * @param reverse    true 表示原分数越小越好（如距离）
     * @return key(docId_chunkIndex) → 归一化后的分数
     */
    private Map<String, Double> normalize(List<Document> docs,
                                          String scoreField,
                                          boolean reverse) {
        if (docs.isEmpty()) {
            return Map.of();
        }

        // ① 提取原始分数
        Map<String, Double> raw = new HashMap<>();
        for (Document doc : docs) {
            Object scoreObj = doc.getMetadata().get(scoreField);
            if (scoreObj == null) {
                continue;
            }
            raw.put(buildKey(doc), ((Number) scoreObj).doubleValue());
        }
        if (raw.isEmpty()) {
            return Map.of();
        }

        // ② 找最小值、最大值
        double min = raw.values().stream()
                .mapToDouble(Double::doubleValue).min().orElse(0);
        double max = raw.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(1);

        // ③ 归一化
        Map<String, Double> normalized = new HashMap<>();
        double range = max - min;

        if (range == 0) {
            raw.keySet().forEach(k -> normalized.put(k, 1.0));
        } else {
            raw.forEach((k, v) -> {
                double norm = (v - min) / range;
                normalized.put(k, reverse ? 1.0 - norm : norm);
            });
        }
        return normalized;
    }
}