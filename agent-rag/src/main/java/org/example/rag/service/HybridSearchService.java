package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务
 * <p>
 * 融合两路召回：
 * ① 向量检索（语义相似）
 * ② 关键词检索（精确匹配）
 * <p>
 * 融合算法：RRF（Reciprocal Rank Fusion）
 * score(d) = Σ 1 / (k + rank_i(d))
 * 其中 k=60 是经验常数（来自 RRF 原始论文）
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

    /**
     * 混合检索（主入口）
     *
     * @param query 用户问题
     * @return Top-K 融合后的 Document 列表
     */
    public List<Document> search(String query) {

        StopWatch sw = new StopWatch("RAG检索");

        int topK = ragProperties.getTopK();
        int recallSize = topK * ragProperties.getRecallMultiplier();

        // ① 向量检索
        sw.start("向量检索");
        List<Document> vectorResults = vectorSearch(query, recallSize);
        sw.stop();

        // ② 关键词检索
        sw.start("关键词检索");
        List<Document> keywordResults = keywordSearchService.search(query, recallSize);
        sw.stop();

        // ③ RRF 融合
        sw.start("RRF融合");
        List<Document> fused = rrfFuse(vectorResults, keywordResults, topK);
        sw.stop();

        // ④ TEI 精排
        sw.start("TEI精排");
        List<Document> reranked = remoteRerankService.rerank(query, fused, topK);
        sw.stop();

        // ★ 一行日志汇总
        log.info("RAG检索完成 query=[{}] 向量={} 关键词={} 融合={} 精排={} 耗时={}ms\n{}",
                truncate(query, 30),
                vectorResults.size(),
                keywordResults.size(),
                fused.size(),
                reranked.size(),
                sw.getTotalTimeMillis(),
                sw.prettyPrint());

        long totalMs = sw.getTotalTimeMillis();
        if (totalMs > 3000) {
            log.warn("⚠️ 慢检索告警(HybridSearchService): query=[{}] 耗时={}ms", truncate(query, 30), totalMs);
        }

        return reranked;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    // ==================== 向量检索 ====================

    /**
     * ① 提取 query 字符串
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
}