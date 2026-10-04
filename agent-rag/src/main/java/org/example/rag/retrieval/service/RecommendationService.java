package org.example.rag.retrieval.service;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.TextUtils;
import org.example.rag.retrieval.config.RecommendationProperties;
import org.example.rag.shared.model.RagFilter;
import org.example.rag.retrieval.model.RecommendedDoc;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * D52 主动推荐服务
 * <p>
 * <b>职责</b>：基于"当前问题 + 历史提问"，从知识库里检索相关文档，
 * 排除已经在回答里引用过的——给用户提供"没看到的补充资料"。
 * <p>
 * <b>与 RagAdvisor 的区别</b>：
 * <ul>
 *   <li>RagAdvisor：检索 → 注入 Prompt → 影响回答</li>
 *   <li>本类：检索 → 追加在回答末尾 → 不影响回答</li>
 * </ul>
 * <b>两者共用 HybridSearchService</b>——检索能力本身只有一份。
 * <p>
 * <b>安全底线</b>：必须传 {@link RagFilter}——推荐不能绕过密级/状态过滤。
 * 用户看不到的文档，推荐里也不能出现。
 */
@Slf4j
@Service
public class RecommendationService {

    private static final String DOWNLOAD_URL_PREFIX = "/fap/rag/file/download/";

    private final HybridSearchService hybridSearchService;
    private final RecommendationProperties props;

    public RecommendationService(HybridSearchService hybridSearchService,
                                 RecommendationProperties props) {
        this.hybridSearchService = hybridSearchService;
        this.props = props;
    }

    /**
     * 检索推荐文档
     *
     * @param currentQuery   用户当前提问（主检索 query）
     * @param historyQueries 历史提问（用于补足，不含当前）
     * @param excludeDocIds  需要排除的 docId（通常是回答已引用的）
     * @param filter         ★ 必须传——安全底线，不能绕过密级/状态
     * @return 推荐列表（最多 props.getTopK() 条）
     */
    public List<RecommendedDoc> recommend(String currentQuery,
                                          List<String> historyQueries,
                                          Set<String> excludeDocIds,
                                          RagFilter filter) {
        if (!props.isEnabled()) {
            return List.of();
        }
        if (currentQuery == null || currentQuery.isBlank()) {
            return List.of();
        }

        // 用 LinkedHashMap 保序 + 去重——docId 是 key
        LinkedHashMap<String, RecommendedDoc> result = new LinkedHashMap<>();

        try {
            // ① 主检索：当前问题
            retrieveAndCollect(currentQuery, excludeDocIds, filter, result);

            // ② 补足：历史提问（结果还不够 topK）
            if (historyQueries != null) {
                for (String q : historyQueries) {
                    if (result.size() >= props.getTopK()) break;
                    if (q == null || q.isBlank() || q.equals(currentQuery)) continue;
                    retrieveAndCollect(q, excludeDocIds, filter, result);
                }
            }

        } catch (Exception e) {
            // ★ 推荐失败不影响主回答——降级为空
            log.warn("[D52] 推荐检索失败，降级为空", e);
            return List.of();
        }

        return result.values().stream()
                .limit(props.getTopK())
                .toList();
    }

    // ==================== 渲染 ====================

    /**
     * 渲染推荐块
     * <p>
     * 格式：
     * <pre>
     * \n\n---\n[RECOMMEND]
     * 📚 你可能还想了解：
     * 1. 《文档名》 — 摘要 [查看](下载链接)
     * 2. ...
     * </pre>
     * <p>
     * <b>为什么用前缀</b>：
     * 前端靠 {@code [RECOMMEND]} 前缀识别——特殊渲染成卡片，
     * 与 {@code [CLARIFY]} 的处理方式保持一致。
     */
    public String render(List<RecommendedDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append(props.getPrefix()).append("\n");
        sb.append("📚 你可能还想了解：\n");

        for (int i = 0; i < docs.size(); i++) {
            RecommendedDoc d = docs.get(i);
            sb.append(i + 1).append(". 《").append(d.title()).append("》");
            if (d.snippet() != null && !d.snippet().isBlank()) {
                sb.append(" — ").append(d.snippet());
            }
            sb.append(" [查看原文](").append(d.downloadUrl()).append(")\n");
        }

        return sb.toString();
    }

    // ==================== 内部 ====================

    /**
     * 检索一次并收集结果
     * <p>
     * 检索 → 过滤 → 打包成 RecommendedDoc → 塞进 result（按 docId 去重）
     */
    private void retrieveAndCollect(String query,
                                    Set<String> excludeDocIds,
                                    RagFilter filter,
                                    LinkedHashMap<String, RecommendedDoc> result) {

        // 用更大的 topK 召回——过滤后可能只剩几条
        List<Document> hits = hybridSearchService.search(
                query, filter, RetrievalProfile.empty(), props.getRecallSize());

        for (Document doc : hits) {
            String docId = (String) doc.getMetadata().get("doc_id");
            if (docId == null || docId.isBlank()) {
                continue;
            }
            if (excludeDocIds != null && excludeDocIds.contains(docId)) {
                continue;   // 已被回答引用——跳过
            }
            if (result.containsKey(docId)) {
                continue;   // 已收集
            }

            result.put(docId, toRecommendedDoc(doc, docId));

            if (result.size() >= props.getTopK()) {
                return;   // 已满——提前退出
            }
        }
    }

    private RecommendedDoc toRecommendedDoc(Document doc, String docId) {
        String source = (String) doc.getMetadata().getOrDefault("source", "未知文档");
        String snippet = TextUtils.truncateWithClean(doc.getText(), props.getMaxSnippetChars());
        double score = extractScore(doc);

        return new RecommendedDoc(
                docId,
                source,
                snippet,
                score,
                DOWNLOAD_URL_PREFIX + docId
        );
    }

    private double extractScore(Document doc) {
        // 优先用 rerank_score；退而求其次用 fused_score；再退用 similarity
        Object v = doc.getMetadata().get("rerank_score");
        if (v == null) v = doc.getMetadata().get("fused_score");
        if (v == null) v = doc.getMetadata().get("score");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}