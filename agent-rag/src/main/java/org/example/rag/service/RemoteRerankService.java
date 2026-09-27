package org.example.rag.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 远程重排序服务 —— 调用 TEI 容器
 */
@Slf4j
@Service
public class RemoteRerankService {

    private final WebClient webClient;
    private final RagProperties ragProperties;

    @PostConstruct
    public void printConfig() {
        log.info("RemoteRerankService 配置：apiUrl={}, timeout={}s, minScore={}",
                ragProperties.getRerank().getApiUrl(),
                ragProperties.getRerank().getTimeoutSeconds(),
                ragProperties.getRerank().getMinScore());
    }

    public RemoteRerankService(WebClient.Builder webClientBuilder,
                               RagProperties ragProperties) {
        this.webClient = webClientBuilder.build();
        this.ragProperties = ragProperties;
    }

    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (topN <= 0) {
            topN = documents.size();
        }

        long start = System.currentTimeMillis();

        List<String> texts = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        Map<String, Object> requestBody = Map.of(
                "query", query,
                "texts", texts
        );

        String apiUrl = ragProperties.getRerank().getApiUrl();
        int timeout = ragProperties.getRerank().getTimeoutSeconds();
        double minScore = ragProperties.getRerank().getMinScore();   // ★ 读阈值

        try {
            RerankResult[] results = webClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(RerankResult[].class)
                    .block(Duration.ofSeconds(timeout));

            if (results == null || results.length == 0) {
                log.warn("TEI 返回为空，返回空结果");
                return List.of();
            }

            // ★ 打印所有原始分数（便于调试阈值）
            log.info("TEI 返回 {} 条，阈值 minScore={}", results.length, minScore);
            for (RerankResult r : results) {
                log.info("  index={}, score={} {}",
                        r.getIndex(), r.getScore(),
                        r.getScore() >= minScore ? "✅ 保留" : "❌ 过滤");
            }

            // ★ 过滤 + 降序 + Top-N
            List<Document> reranked = Arrays.stream(results)
                    .filter(r -> r.getScore() >= minScore)                     // 过滤低分
                    .sorted(Comparator.comparingDouble(RerankResult::getScore).reversed())  // 降序
                    .limit(topN)
                    .map(r -> {
                        Document doc = documents.get(r.getIndex());
                        doc.getMetadata().put("rerank_score", r.getScore());
                        return doc;
                    })
                    .collect(Collectors.toList());

            long cost = System.currentTimeMillis() - start;

            if (reranked.isEmpty()) {
                log.warn("TEI 重排后全部低于阈值 {}，返回空结果（耗时 {} ms）", minScore, cost);
            } else {
                log.info("TEI 重排序完成：输入 {} 条 → 通过阈值保留 {} 条，耗时 {} ms",
                        documents.size(), reranked.size(), cost);

                for (int i = 0; i < Math.min(3, reranked.size()); i++) {
                    Document doc = reranked.get(i);
                    log.info("  Top{}: score={}, docId={}",
                            i + 1,
                            doc.getMetadata().get("rerank_score"),
                            doc.getMetadata().get("doc_id"));
                }
            }

            return reranked;

        } catch (Exception e) {
            log.error("调用 TEI 失败，返回空结果", e);
            return List.of();   // 出错也别返回不相关结果
        }
    }

    @Data
    public static class RerankResult {
        @JsonProperty("index")
        private int index;

        @JsonProperty("score")
        private double score;
    }
}