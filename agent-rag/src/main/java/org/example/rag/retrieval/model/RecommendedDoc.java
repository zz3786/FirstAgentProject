package org.example.rag.retrieval.model;

/**
 * D52 推荐文档
 * <p>
 * 只包含前端渲染需要的字段——不暴露 metadata 全貌。
 */
public record RecommendedDoc(
        String docId,
        String title,      // 文件名（从 metadata.source 取）
        String snippet,    // 内容摘要
        double score,      // rerank 分数
        String downloadUrl // /fap/rag/file/download/{docId}
) {}