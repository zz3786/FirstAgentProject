package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 文档入库服务
 * <p>
 * 完整链路：解析 → 切片 → 向量化（调阿里云）→ 存入 Qdrant
 */
@Slf4j
@Service
public class DocumentIngestService {

    private final VectorStore vectorStore;
    private final DocumentParseService parseService;

    public DocumentIngestService(VectorStore vectorStore,
                                 DocumentParseService parseService) {
        this.vectorStore = vectorStore;
        this.parseService = parseService;
    }

    /**
     * 入库 PDF
     */
    public int ingestPdf(Resource resource) {
        List<Document> documents = parseService.parsePdf(resource);

        TokenTextSplitter splitter = new TokenTextSplitter(
                500,    // chunkSize
                100,    // minChunkSizeChars
                10,     // minChunkLengthToEmbed
                5000,   // maxNumChunks
                true,    // keepSeparator
                List.of('.', '!', '?', '\n', '。', '！', '？', '；', ';')  // ← 新增：标点符号列表
        );
        List<Document> chunks = splitter.apply(documents);
        log.info("切片完成，chunk 数: {}", chunks.size());

        vectorStore.add(chunks);
        log.info("入库完成，共 {} 个切片", chunks.size());

        return chunks.size();
    }

    /**
     * 入库其他格式（Word 等）
     */
    public int ingestWithTika(Resource resource) {
        List<Document> documents = parseService.parseWithTika(resource);

        TokenTextSplitter splitter = new TokenTextSplitter(500, 100, 10, 5000, true,List.of('.', '!', '?', '\n', '。', '！', '？', '；', ';'));  // ← 新增：标点符号列表);
        List<Document> chunks = splitter.apply(documents);

        vectorStore.add(chunks);
        log.info("入库完成（Tika），共 {} 个切片", chunks.size());
        return chunks.size();
    }
}