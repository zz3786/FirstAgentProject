package org.example.rag;

import org.example.rag.config.RagProperties;
import org.example.rag.service.DocumentIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RagSmokeTest {

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagProperties ragProperties;

    @Test
    void testIngestAndSearch() {
        // ① 入库
        String filePath = ragProperties.getFilesDir()+"家庭医生有偿签约服务协议书.docx";

        int chunks = ingestService.ingestWithTika(
                new FileSystemResource(filePath)
        );
        assertTrue(chunks > 0, "应至少产生 1 个切片");
        System.out.println("入库切片数: " + chunks);

        // ② 检索——先用具体词、不要阈值
        SearchRequest request = SearchRequest.builder()
                .query("签约家庭医生时要注意什么")       // ← 改成文档里出现的词
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())     // ← 注释掉
                .build();

        List<Document> results = vectorStore.similaritySearch(request);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "应检索到相关片段");

        System.out.println("检索到 " + results.size() + " 个片段:");
        results.forEach(doc -> {
            System.out.println("--- 片段 ---");
            // 打印相似度（如果有）
            Object score = doc.getMetadata().get("score");
            System.out.println("相似度: " + score);
            System.out.println("内容: " + doc.getText().substring(0, Math.min(150, doc.getText().length())));
        });
    }
}