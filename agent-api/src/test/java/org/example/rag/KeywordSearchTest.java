package org.example.rag;

import org.example.rag.shared.mapper.RagChunkMapper;
import org.example.rag.retrieval.service.KeywordSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 关键字检索
 */
@SpringBootTest
public class KeywordSearchTest {

    @Autowired
    private KeywordSearchService keywordSearchService;

    @Autowired
    private RagChunkMapper ragChunkMapper;

    @Test
    void testCount() {
        System.out.println("总 chunk 数: " + ragChunkMapper.count());
    }

    @Test
    void testSearch() {
        List<Document> results = keywordSearchService.search("家庭医生 签约", 5);
        System.out.println("检索到 " + results.size() + " 条");
        results.forEach(doc -> {
            System.out.println("score=" + doc.getMetadata().get("score"));
            System.out.println("content=" + doc.getText().substring(0, Math.min(100, doc.getText().length())));
            System.out.println("---");
        });
    }
}
