package org.example.rag.retrieval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D55：检索配置
 * <p>
 * 前缀：{@code app.rag.retrieval.*}
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag.retrieval")
public class RetrievalProperties {

    /** 检索 topK */
    private int topK = 5;

    /** 相似度阈值（<=0 表示不过滤） */
    private double similarityThreshold = 0.5;

    /** RRF 常数 */
    private int rrfK = 60;

    /** 每路召回放大倍数 */
    private int recallMultiplier = 2;

    /** 融合策略：weighted / rrf */
    private String fusionStrategy = "weighted";

    /** 向量检索权重 */
    private double vectorWeight = 0.7;

    /** 关键词检索权重 */
    private double keywordWeight = 0.3;

    /** 远程重排序配置 */
    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        private String apiUrl = "http://localhost:8081/rerank";
        private int timeoutSeconds = 60;
        private double minScore = 0.3;
    }
}