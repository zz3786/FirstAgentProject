package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * RAG 文档入库服务
 * <p>
 * 完整链路：
 * ① 复制原文件到可访问目录（用于后续点击来源链接打开）
 * ② 解析（Tika / PDFBox）
 * ③ 切片
 * ④ 注入 metadata（doc_id、source、file_path）
 * ⑤ 向量化（调阿里云）
 * ⑥ 存入 Qdrant
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

    public DocumentIngestService(VectorStore vectorStore,
                                 DocumentParseService parseService, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.parseService = parseService;
        this.ragProperties = ragProperties;
    }

    // ==================== 入库 PDF ====================

    /**
     * 入库 PDF
     */
    public int ingestPdf(Resource resource) {
        // ① 复制原文件 + 生成 docId
        DocInfo docInfo = saveFile(resource);

        // ② 解析
        List<Document> documents = parseService.parsePdf(resource);

        // ③ 切片
        List<Document> chunks = split(documents);
        log.info("PDF 切片完成，chunk 数: {}", chunks.size());

        // ④ 注入 metadata
        injectMetadata(chunks, docInfo);

        // ⑤ 入库
        vectorStore.add(chunks);
        log.info("PDF 入库完成，docId={}, source={}, 切片数={}",
                docInfo.docId(), docInfo.originalName(), chunks.size());

        return chunks.size();
    }

    // ==================== 入库其他格式（Word 等） ====================

    /**
     * 入库 Word / PPT / HTML 等（Tika 万能解析）
     */
    public int ingestWithTika(Resource resource) {
        // ① 复制原文件 + 生成 docId
        DocInfo docInfo = saveFile(resource);

        // ② 解析
        List<Document> documents = parseService.parseWithTika(resource);

        // ③ 切片
        List<Document> chunks = split(documents);
        log.info("Tika 切片完成，chunk 数: {}", chunks.size());

        // ④ 注入 metadata
        injectMetadata(chunks, docInfo);

        // ⑤ 入库
        vectorStore.add(chunks);
        log.info("Tika 入库完成，docId={}, source={}, 切片数={}",
                docInfo.docId(), docInfo.originalName(), chunks.size());

        return chunks.size();
    }

    // ==================== 内部方法 ====================

    /**
     * 切片
     */
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

        // 提取扩展名（如 .docx / .pdf）
        String ext = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = originalName.substring(dotIdx);   // 含点：.docx
        }

        String docId = UUID.randomUUID().toString();
        // ★ 存储文件名 = {docId}{扩展名} —— 不带原始文件名
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
     * 文件信息（内部记录）
     */
    private record DocInfo(String docId, String originalName, String storedName) {}
}