package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.example.rag.entity.RagChunk;
import org.example.rag.mapper.RagChunkMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RAG 文档入库服务
 * <p>
 * 完整链路：
 * ① 复制原文件到可访问目录
 * ② 解析（Tika / PDFBox）
 * ③ 切片
 * ④ 注入 metadata（doc_id、source、file_path）
 * ⑤ 向量化 → 存 Qdrant
 * ⑥ 存 MySQL（关键词检索用）
 */
@Slf4j
@Service
public class DocumentIngestService {

    /** 切片配置 */
    private static final int CHUNK_SIZE = 500;
    private static final int MIN_CHUNK_SIZE_CHARS = 100;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 10;
    private static final int MAX_NUM_CHUNKS = 5000;
    private static final boolean KEEP_SEPARATOR = true;
    private static final List<Character> PUNCTUATIONS = List.of(
            '.', '!', '?', '\n', '。', '！', '？', '；', ';'
    );

    private final VectorStore vectorStore;
    private final DocumentParseService parseService;
    private final RagProperties ragProperties;
    private final RagChunkMapper ragChunkMapper;

    public DocumentIngestService(VectorStore vectorStore,
                                 DocumentParseService parseService,
                                 RagProperties ragProperties,
                                 RagChunkMapper ragChunkMapper) {
        this.vectorStore = vectorStore;
        this.parseService = parseService;
        this.ragProperties = ragProperties;
        this.ragChunkMapper = ragChunkMapper;
    }

    // ==================== 入库 PDF ====================

    /**
     * 入库 PDF 带重试
     *
     * @return DocInfo（含 docId、原文件名、存储文件名）
     */
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public DocInfo ingestPdf(Resource resource) {
        // ① 复制原文件 + 生成 docId
        DocInfo docInfo = saveFile(resource);

        // ② 解析
        List<Document> documents = parseService.parsePdf(resource);

        // ③ 切片
        List<Document> chunks = split(documents);
        log.info("PDF 切片完成，chunk 数: {}", chunks.size());

        // ④ 注入 metadata
        injectMetadata(chunks, docInfo);

        // ⑤ 存向量库
        vectorStore.add(chunks);

        // ⑥ 存 MySQL（★ 补上——之前漏了）
        saveToMysql(chunks, docInfo);

        log.info("PDF 入库完成，docId={}, source={}, 切片数={}",
                docInfo.docId(), docInfo.originalName(), chunks.size());

        return docInfo;   // ★ 返回 DocInfo
    }

    // ==================== 入库其他格式（Word 等） ====================

    /**
     * 入库 Word / PPT / HTML 等（Tika 万能解析）  带重试
     *
     * @return DocInfo
     */
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public DocInfo ingestWithTika(Resource resource) {
        StopWatch sw = new StopWatch("文档入库");
        String originalName = resource.getFilename();

        // ① 复制文件
        sw.start("复制文件");
        DocInfo docInfo = saveFile(resource);
        sw.stop();

        // ② 解析
        sw.start("解析");
        List<Document> documents = parseService.parseWithTika(resource);
        sw.stop();

        // ③ 切片
        sw.start("切片");
        List<Document> chunks = split(documents);
        sw.stop();

        // ④ 注入 metadata
        sw.start("注入metadata");
        injectMetadata(chunks, docInfo);
        sw.stop();

        // ⑤ 向量化 + 存 Qdrant
        sw.start("向量化+Qdrant");
        vectorStore.add(chunks);
        sw.stop();

        // ⑥ 存 MySQL
        sw.start("存MySQL");
        saveToMysql(chunks, docInfo);
        sw.stop();

        log.info("文档入库完成 file=[{}] chunk数={} 总耗时={}ms\n{}",
                originalName,
                chunks.size(),
                sw.getTotalTimeMillis(),
                sw.prettyPrint());

        return docInfo;
    }

    // ==================== 内部方法 ====================

    private List<Document> split(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(
                CHUNK_SIZE,
                MIN_CHUNK_SIZE_CHARS,
                MIN_CHUNK_LENGTH_TO_EMBED,
                MAX_NUM_CHUNKS,
                KEEP_SEPARATOR,
                PUNCTUATIONS
        );
        return splitter.apply(documents);
    }

    /**
     * 复制原文件到存储目录 + 生成 docId
     */
    private DocInfo saveFile(Resource resource) {
        String originalName = resource.getFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed-file";
        }

        // 提取扩展名（含点）
        String ext = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = originalName.substring(dotIdx);
        }

        String docId = UUID.randomUUID().toString();
        String storedName = docId + ext;

        try {
            Path storageDir = Path.of(ragProperties.getFileStorageDir());
            Files.createDirectories(storageDir);

            Path targetPath = storageDir.resolve(storedName);

            try (var in = resource.getInputStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("原文件已保存: {}（原名：{}）", targetPath, originalName);

        } catch (IOException e) {
            log.error("保存原文件失败: {}", originalName, e);
        }

        return new DocInfo(docId, originalName, storedName);
    }

    /**
     * 给每个 chunk 注入 metadata
     */
    private void injectMetadata(List<Document> chunks, DocInfo docInfo) {
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("doc_id", docInfo.docId());
            chunk.getMetadata().put("source", docInfo.originalName());
            chunk.getMetadata().put("file_path", docInfo.storedName());
            chunk.getMetadata().put("chunk_index", i);
            chunk.getMetadata().put("total_chunks", chunks.size());
        }
    }

    /**
     * 存 MySQL（供关键词检索）
     */
    private void saveToMysql(List<Document> chunks, DocInfo docInfo) {
        List<RagChunk> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            RagChunk entity = new RagChunk();
            entity.setDocId(docInfo.docId());
            entity.setChunkIndex(i);
            entity.setSource(docInfo.originalName());
            entity.setContent(chunk.getText());
            entity.setFilePath(docInfo.storedName());
            entities.add(entity);
        }
        ragChunkMapper.batchInsert(entities);
        log.info("MySQL 同步完成，{} 条", entities.size());
    }

    /**
     * 文件信息 —— ★ 改成 public，供增量更新使用
     */
    public record DocInfo(String docId, String originalName, String storedName) {}
}