package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.example.rag.model.RagFilter;
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
    private final RagProperties ragProperties;

    public HybridSearchService(VectorStore vectorStore,
                               KeywordSearchService keywordSearchService, RemoteRerankService remoteRerankService,
                               RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.keywordSearchService = keywordSearchService;
        this.remoteRerankService = remoteRerankService;
        this.ragProperties = ragProperties;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    /**
     * 混合检索（主入口）
     * 用户提问
     *     ↓
     * ① 向量检索（Qdrant）→ 20 条候选
     * ② 关键词检索（MySQL）→ 20 条候选
     *     ↓
     * ③ 加权融合（w_vec=0.7, w_kw=0.3）
     *     ↓
     * ④ TEI 精排 → Top-5
     *     ↓
     * ⑤ Prompt 注入 → DeepSeek
     * @param query 用户问题
     * @return Top-K 融合后的 Document 列表
     */
    public List<Document> search(String query) {
        return search(query, null);
    }


    /**
     * 混合检索（主入口）—— D46 支持元数据过滤
     *
     * @param query  用户问题
     * @param filter 过滤条件（null 或 empty 表示不过滤）
     * @return Top-K 融合后的 Document 列表
     */
    public List<Document> search(String query, RagFilter filter) {
        StopWatch sw = new StopWatch("RAG检索");

        int topK = ragProperties.getTopK();
        int recallSize = topK * ragProperties.getRecallMultiplier();

        // ① 向量检索（★ 带过滤）
        sw.start("向量检索");
        List<Document> vectorResults = vectorSearch(query, recallSize, filter);
        sw.stop();

        // ② 关键词检索（★ 带过滤）
        sw.start("关键词检索");
        List<Document> keywordResults = keywordSearchService.search(query, recallSize, filter);
        sw.stop();

        log.info("混合检索：向量 {} 条，关键词 {} 条，过滤条件={}",
                vectorResults.size(), keywordResults.size(),
                filter == null || filter.isEmpty() ? "无" : filter);

        // ③ 融合（不变）
        sw.start("RRF融合");
        List<Document> fused;
        if ("weighted".equalsIgnoreCase(ragProperties.getFusionStrategy())) {
            fused = weightedFuse(vectorResults, keywordResults, topK);
        } else {
            fused = rrfFuse(vectorResults, keywordResults, topK);
        }
        sw.stop();

        // ④ TEI 精排（不变）
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

     * @param query
     * @param topK
     * @return
     */
    private List<Document> vectorSearch(String query, int topK) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);

            // 相似度阈值（>0 才启用）
            if (ragProperties.getSimilarityThreshold() > 0) {
                builder.similarityThreshold(ragProperties.getSimilarityThreshold());
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            return results != null ? results : List.of();

        } catch (Exception e) {
            log.error("向量检索失败, query={}", query, e);
            return List.of();
        }
    }

    /**
     * 向量检索（带过滤）
     * <p>
     * ★ D46 关键改动：SearchRequest.builder() 增加 filterExpression(...)
     * <p>
     * <b>为什么过滤放在向量检索阶段而非检索后</b>：
     * Qdrant 在 HNSW 图遍历时就能跳过不匹配的点，
     * 不需要先召回 Top-K 再丢弃——这在高并发下节省大量计算。
     *
\    * ① 提取 query 字符串
     *    "家庭医生签约注意事项"
     *         ↓
     * ② 调用 EmbeddingModel.embed(query)     ← ★ 这里把问题转向量
     *         ↓
     *    阿里云 text-embedding-v4 返回 [0.12, -0.34, ...] (1024 维)
     *         ↓
     * ③ 拿这个向量去 Qdrant 检索
     *    POST /collections/agent-rag/points/search
     *    body: { "vector": [0.12, -0.34, ...], "limit": 10 }
     *         ↓
     * ④ Qdrant 返回 Top-K 相似片段
     *         ↓
     * ⑤ 包装成 List<Document> 返回给你
     *
     * 关键：第 ② 步是框架自动做的——你不用手动调 embedding。
     */
    private List<Document> vectorSearch(String query, int topK, RagFilter filter) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);

            if (ragProperties.getSimilarityThreshold() > 0) {
                builder.similarityThreshold(ragProperties.getSimilarityThreshold());
            }

            // ★ 施加过滤条件
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
        // key = docId_chunkIndex，用于去重
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // ① 向量检索的 RRF 贡献
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            Document doc = vectorResults.get(rank);
            String key = buildKey(doc);
            double rrfScore = 1.0 / (ragProperties.getRrfK() + rank + 1);
            rrfScores.merge(key, rrfScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        }

        // ② 关键词检索的 RRF 贡献
        for (int rank = 0; rank < keywordResults.size(); rank++) {
            Document doc = keywordResults.get(rank);
            String key = buildKey(doc);
            double rrfScore = 1.0 / (ragProperties.getRrfK() + rank + 1);
            rrfScores.merge(key, rrfScore, Double::sum);
            docMap.putIfAbsent(key, doc);
        }

        // ③ 按 RRF 分数降序排列，取 Top-K
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    // 把 RRF 分数写进 metadata，便于调试
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


    /**
     * 加权融合（归一化线性加权）
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
     * ③ 加权求和：fused = wVec × norm_vec + wKw × norm_kw
     * ④ 降序排序取 Top-K
     * <p>
     * ═══════════════════════════════════════════════════════════════
     * 【举例】（wVec=0.7, wKw=0.3）
     * ═══════════════════════════════════════════════════════════════
     * 向量路：docA(distance=0.15), docB(0.35), docC(0.80)
     * 关键词路：docB(score=3.28), docD(1.50), docA(0.90)
     * <p>
     * 归一化后（都变成"越大越好"）：
     * ┌──────┬──────────────┬──────────────┐
     * │ doc  │ 向量归一化   │ 关键词归一化 │
     * ├──────┼──────────────┼──────────────┤
     * │ docA │    1.00      │    0.00      │
     * │ docB │    0.69      │    1.00      │
     * │ docC │    0.00      │     -        │
     * │ docD │     -        │    0.25      │
     * └──────┴──────────────┴──────────────┘
     * <p>
     * 加权求和：
     * docA: 0.7×1.00 + 0.3×0.00 = 0.70
     * docB: 0.7×0.69 + 0.3×1.00 = 0.78  ← 两路都命中，最高
     * docC: 0.7×0.00            = 0.00
     * docD:           0.3×0.25  = 0.075
     * <p>
     * 最终排序：docB > docA > docD > docC
     *
     * @param vectorResults  向量检索结果（metadata 里含 "distance" 字段）
     * @param keywordResults 关键词检索结果（metadata 里含 "score" 字段）
     * @param topK           最终返回条数
     * @return 加权融合后的 Top-K 文档
     */
    private List<Document> weightedFuse(List<Document> vectorResults,
                                        List<Document> keywordResults,
                                        int topK) {

        // ① 归一化向量分数
        //    distance 越小越好 → reverse=true → 反转后 1.0 表示最相关
        Map<String, Double> vectorScores = normalize(
                vectorResults, "distance", true);

        // ② 归一化关键词分数
        //    score 越大越好 → reverse=false → 1.0 表示最相关
        Map<String, Double> keywordScores = normalize(
                keywordResults, "score", false);

        // ③ 合并
        //    fusedScores：key(docId_chunkIndex) → 加权总分
        //    docMap：     key → Document 对象（保留原始文档，避免重复）
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        double wVec = ragProperties.getVectorWeight();
        double wKw = ragProperties.getKeywordWeight();

        // ---- 向量路贡献 ----
        // 遍历向量路结果，把 wVec × 归一化分数 累加进 fusedScores
        vectorResults.forEach(doc -> {
            String key = buildKey(doc);                                // 唯一标识（docId_chunkIndex）
            double normScore = vectorScores.getOrDefault(key, 0.0);    // 取归一化后的分数

            // merge 的语义：
            //   key 不存在 → 直接 put(key, wVec*normScore)
            //   key 已存在 → 用 Double::sum 把新值累加到旧值上
            fusedScores.merge(key, wVec * normScore, Double::sum);

            // putIfAbsent：首次遇到该 doc 时存入，避免重复覆盖
            docMap.putIfAbsent(key, doc);
        });

        // ---- 关键词路贡献 ----
        // 注意：这里同样用 merge 累加 —— 两路都命中的文档分数会叠加，排名自然更高
        keywordResults.forEach(doc -> {
            String key = buildKey(doc);
            double normScore = keywordScores.getOrDefault(key, 0.0);
            fusedScores.merge(key, wKw * normScore, Double::sum);      // 累加到已有分数
            docMap.putIfAbsent(key, doc);
        });

        // ④ 按总分降序排序，取 Top-K
        return fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))   // 按 value 降序
                .limit(topK)                                                     // 取前 K 个
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    // 把融合分数写回 metadata —— 便于调试、日志、前端展示
                    doc.getMetadata().put("fused_score", e.getValue());
                    return doc;
                })
                .toList();
    }

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
        // 空列表——直接返回空 Map
        if (docs.isEmpty()) {
            return Map.of();
        }

        // ① 提取原始分数
        Map<String, Double> raw = new HashMap<>();
        for (Document doc : docs) {
            Object scoreObj = doc.getMetadata().get(scoreField);
            if (scoreObj == null) {
                continue;                    // 跳过无分数的文档
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
            // 所有分数相同 —— 无法归一化 —— 统一设为 1.0（都一样相关）
            raw.keySet().forEach(k -> normalized.put(k, 1.0));
        } else {
            raw.forEach((k, v) -> {
                double norm = (v - min) / range;               // 映射到 [0,1]
                // reverse=true 时反转：1.0 - norm，把"越小越好"变成"越大越好"
                normalized.put(k, reverse ? 1.0 - norm : norm);
            });
        }
        return normalized;
    }
}