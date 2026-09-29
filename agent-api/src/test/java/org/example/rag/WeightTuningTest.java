package org.example.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.example.rag.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;

@Slf4j
@SpringBootTest
class WeightTuningTest {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private RagProperties ragProperties;

    @Data
    static class EvalCase {
        private String query;
        private List<String> expectedDocKeywords;
    }

    @Test
    void gridSearch() throws Exception {
        // 加载评估集
        List<EvalCase> cases = new ObjectMapper().readValue(
                new ClassPathResource("eval-queries.json").getInputStream(),
                new TypeReference<List<EvalCase>>() {}
        );

        System.out.println("\n========== 权重网格搜索 ==========");
        System.out.printf("%-10s %-10s %-15s%n", "w_vec", "w_kw", "HitRate@5");
        System.out.println("-".repeat(40));

        // 遍历权重组合
        double[] vecWeights = {0.0, 0.3, 0.5, 0.7, 1.0};
        double bestWeight = 0.7;
        double bestHitRate = 0;

        for (double wVec : vecWeights) {
            double wKw = 1.0 - wVec;

            // 临时设置权重（通过反射或 setter）
            ragProperties.setVectorWeight(wVec);
            ragProperties.setKeywordWeight(wKw);

            // 计算命中率
            double hitRate = evaluate(cases);
            System.out.printf("%-10.1f %-10.1f %-15.2f%n", wVec, wKw, hitRate);

            if (hitRate > bestHitRate) {
                bestHitRate = hitRate;
                bestWeight = wVec;
            }
        }

        System.out.println("-".repeat(40));
        System.out.printf("最佳权重：w_vec=%.1f, w_kw=%.1f, HitRate=%.2f%n",
                bestWeight, 1 - bestWeight, bestHitRate);
        System.out.println("=================================\n");
    }

    /**
     * 评估一批查询的 HitRate@5
     */
    private double evaluate(List<EvalCase> cases) {
        int hits = 0;
        for (EvalCase c : cases) {
            List<Document> results = hybridSearchService.search(c.getQuery());

            // 判断 Top-5 里有没有包含期望关键词的文档
            boolean hit = results.stream()
                    .limit(5)
                    .anyMatch(doc -> c.getExpectedDocKeywords().stream()
                            .anyMatch(kw -> doc.getText().contains(kw)));

            if (hit) hits++;
        }
        return (double) hits / cases.size();
    }
}