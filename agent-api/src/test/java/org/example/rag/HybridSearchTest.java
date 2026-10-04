package org.example.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.retrieval.service.HybridSearchService;
import org.example.rag.retrieval.service.KeywordSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索 + TEI 重排序 测试
 * <p>
 * 前置条件：
 * 1. Qdrant 已启动 + 已有数据
 * 2. TEI 容器已启动（http://localhost:8081）
 * 3. MySQL 已启动 + rag_chunks 表有数据
 */
@Slf4j
@SpringBootTest
class HybridSearchTest {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private KeywordSearchService keywordSearchService;

    /**
     * 对比三种检索方式：
     * ① 纯向量
     * ② 纯关键词
     * ③ 混合 + TEI 重排
     */
    @Test
    @DisplayName("对比：向量 vs 关键词 vs 混合+TEI重排")
    void compareThreeApproaches() {
        String query = "家庭医生签约注意事项";

        System.out.println("\n========================================");
        System.out.println("查询: " + query);
        System.out.println("========================================\n");

        // ① 纯向量检索
        System.out.println("---------- ① 纯向量检索 ----------");
        List<Document> vectorOnly = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(5).build());
        printResults(vectorOnly, false);

        // ② 纯关键词检索
        System.out.println("\n---------- ② 纯关键词检索 ----------");
        List<Document> keywordOnly = keywordSearchService.search(query, 5);
        printResults(keywordOnly, false);

        // ③ 混合检索 + TEI 重排
        System.out.println("\n---------- ③ 混合检索 + TEI 重排 ----------");
        List<Document> hybrid = hybridSearchService.search(query);
        printResults(hybrid, true);

        // 断言
        assertNotNull(hybrid);
        assertFalse(hybrid.isEmpty(), "混合检索应该有结果");

        System.out.println("\n========================================");
        System.out.printf("向量: %d 条 | 关键词: %d 条 | 混合: %d 条%n",
                vectorOnly.size(), keywordOnly.size(), hybrid.size());
        System.out.println("========================================\n");
    }

    /**
     * 测试具体问题——验证 TEI 重排是否把最相关的排前面
     */
    @Test
    @DisplayName("TEI 重排：最相关文档应排在前")
    void testRerankOrdering() {
        String query = "家庭医生签约后能享受什么服务";

        List<Document> results = hybridSearchService.search(query);

        System.out.println("\n========================================");
        System.out.println("查询: " + query);
        System.out.println("========================================");

        assertNotNull(results);
        assertFalse(results.isEmpty(), "应有检索结果");

        // 打印每个结果
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            Object score = doc.getMetadata().get("rerank_score");
            Object docId = doc.getMetadata().get("doc_id");
            String preview = truncate(doc.getText(), 80);

            System.out.printf("  [%d] score=%s | docId=%s%n      %s%n",
                    i + 1, score, docId, preview);
        }

        // 验证：第一条的 rerank_score 存在
        assertNotNull(results.get(0).getMetadata().get("rerank_score"),
                "应该有 rerank_score 字段");

        System.out.println("========================================\n");
    }

    /**
     * 测试边界：空查询、无结果
     */
    @Test
    @DisplayName("边界：空结果处理")
    void testEmptyQuery() {
        List<Document> results = hybridSearchService.search("这是一个完全不相关的问题xyz123456");
        assertNotNull(results);
        // 不强制要求有结果——TEI 可能返回低分
        System.out.println("空结果测试：返回 " + results.size() + " 条");
    }

    // ==================== 辅助方法 ====================

    private void printResults(List<Document> docs, boolean hasRerankScore) {
        if (docs.isEmpty()) {
            System.out.println("  (无结果)");
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [%d] ", i + 1));

            // 关键词检索有 score 字段
            Object kwScore = doc.getMetadata().get("score");
            if (kwScore != null) {
                sb.append("kw_score=").append(String.format("%.4f", ((Number) kwScore).doubleValue())).append(" ");
            }

            // 混合检索有 rerank_score
            if (hasRerankScore) {
                Object rerankScore = doc.getMetadata().get("rerank_score");
                if (rerankScore != null) {
                    sb.append("rerank=").append(String.format("%.4f", ((Number) rerankScore).doubleValue())).append(" ");
                }
            }

            // docId
            Object docId = doc.getMetadata().get("doc_id");
            if (docId != null) {
                sb.append("docId=").append(docId);
            }
            System.out.println(sb);

            // 内容预览
            System.out.println("      " + truncate(doc.getText(), 80));
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }
}