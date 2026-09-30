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
import java.util.Map;
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
                                 DocumentParseService parseService, TableExtractService tableExtractService,
                                 RagProperties ragProperties,
                                 RagChunkMapper ragChunkMapper) {
        this.vectorStore = vectorStore;
        this.parseService = parseService;
        this.ragProperties = ragProperties;
        this.ragChunkMapper = ragChunkMapper;
    }


    // ==================== 入库其他格式（Word 等） ====================

    /**
     * 文档入库主入口（自动分派）
     * <p>
     * 按文件类型分派到对应的解析器：
     * - Excel / CSV  → TableExtractService（已按行分块）
     * - 图片         → ImageDocumentReader + OcrService
     * - PDF          → PagePdfDocumentReader（含扫描件 OCR 补丁）
     * - 其他         → Tika 万能解析
     * <p>
     * 6 段流程：复制 → 解析 → 切片 → 注入 metadata → 向量化 → 存 MySQL
     *
     * @return 入库文档信息（docId / 原始文件名 / 存储名）
     */
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public DocInfo ingest(Resource resource) {
        StopWatch sw = new StopWatch("文档入库");
        String originalName = resource.getFilename();

        // ① 复制文件
        sw.start("复制文件");
        DocInfo docInfo = saveFile(resource);
        sw.stop();

        // ② 解析（内部自动分派：表格 → TableExtractService，其他 → Tika）
        sw.start("解析");
        DocumentParseService.ParseResult parsed = parseService.parse(resource);
        sw.stop();

        // ③ 切片（表格路径已自带 chunk，不重复切）
        sw.start("切片");
        List<Document> chunks = parsed.alreadyChunked() ? parsed.documents() : split(parsed.documents());
        sw.stop();

        // ④ 注入 metadata（幂等，两条路径统一入口）
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

        log.info("文档入库完成 file=[{}] chunk数={} 总耗时={}ms\n{}",originalName, chunks.size(), sw.getTotalTimeMillis(), sw.prettyPrint());

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
     * 注入元数据（幂等）
     * <p>
     * <b>D46 新增</b>：业务过滤维度（department / year / content_type）
     * <p>
     * <b>这些字段从哪来</b>：
     * - 从文件路径推断（如 D:/testVectorData/财务部/2024制度.docx）
     * - 或从解析器产出（如 TableExtractService 已填 content_type=table）
     * - 或从外部配置（如按目录映射部门）
     * <p>
     * <b>为什么在 injectMetadata 里填而不在解析器里填</b>：
     * 这是"业务维度的统一补全"——所有路径都要有这些字段。
     * 解析器只管"文件怎么变文本"，不该感知业务分类。
     */
    private void injectMetadata(List<Document> chunks, DocInfo docInfo) {
        String department = inferDepartment(docInfo.originalName());
        Integer year = inferYear(docInfo.originalName());

        // ★ D46 新增：从文件名推断密级和状态（生产环境建议改为入库参数）
        Integer securityLevel = inferSecurityLevel(docInfo.originalName());
        String status = inferStatus(docInfo.originalName());

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = chunk.getMetadata();

            // 通用文档标识
            meta.putIfAbsent("doc_id", docInfo.docId());
            meta.putIfAbsent("source", docInfo.originalName());
            meta.putIfAbsent("file_path", docInfo.storedName());
            meta.putIfAbsent("chunk_index", i);
            meta.putIfAbsent("total_chunks", chunks.size());

            // D46 过滤维度
            if (department != null) {
                meta.putIfAbsent("department", department);
            }
            if (year != null) {
                meta.putIfAbsent("year", year);
            }
            meta.putIfAbsent("content_type", "text");

            // ★ D46 新增：密级和状态（有默认值，永远不会 null）
            meta.putIfAbsent("security_level",
                    securityLevel != null ? securityLevel : 1);
            meta.putIfAbsent("status",
                    status != null ? status : "active");
        }
    }

    /**
     * 从文件名推断密级
     * <p>
     * 示例规则：
     * - 含"机密"/"绝密" → 4
     * - 含"秘密" → 3
     * - 含"内部" → 2
     * - 其他 → 1（公开）
     * <p>
     * 生产环境建议从目录结构、数据库配置、或入库参数取——比文件名可靠。
     */
    private Integer inferSecurityLevel(String fileName) {
        if (fileName == null) {
            return 1;
        }
        if (fileName.contains("绝密") || fileName.contains("机密")) {
            return 4;
        }
        if (fileName.contains("秘密")) {
            return 3;
        }
        if (fileName.contains("内部")) {
            return 2;
        }
        return 1;
    }

    /**
     * 从文件名推断状态
     * <p>
     * 示例规则：
     * - 含"作废"/"废止"/"旧版" → deprecated
     * - 含"草稿"/"draft" → draft
     * - 含"归档" → archived
     * - 其他 → active
     */
    private String inferStatus(String fileName) {
        if (fileName == null) {
            return "active";
        }
        String lower = fileName.toLowerCase();
        if (lower.contains("作废") || lower.contains("废止") || lower.contains("旧版")) {
            return "deprecated";
        }
        if (lower.contains("草稿") || lower.contains("draft")) {
            return "draft";
        }
        if (lower.contains("归档") || lower.contains("archived")) {
            return "archived";
        }
        return "active";
    }

    /**
     * 从文件名/路径推断部门
     * <p>
     * <b>实现方式取决于你的文件组织约定</b>。
     * 示例假设：D:/testVectorData/{部门}/{文件名}.docx
     * <p>
     * 生产环境建议改为"在入库接口传入 department 参数"或"从数据库配置表读取"，
     * 比路径推断可靠得多。
     */
    private String inferDepartment(String fileName) {
        if (fileName == null) {
            return null;
        }
        // 示例：文件名含"财务"→财务部，"人事/HR"→人事部
        if (fileName.contains("财务")) {
            return "财务部";
        }
        if (fileName.contains("人事") || fileName.contains("HR")) {
            return "人事部";
        }
        if (fileName.contains("研发") || fileName.contains("技术")) {
            return "研发部";
        }
        if (fileName.contains("市场") || fileName.contains("营销")) {
            return "市场部";
        }
        return null;   // 推断不出就不填——不编造
    }

    /**
     * 从文件名推断年份
     * <p>
     * 示例：文件名含 "2024" → year=2024。
     * 生产环境建议从文件属性或入库参数取，别靠正则猜。
     */
    private Integer inferYear(String fileName) {
        if (fileName == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(20\\d{2})")
                .matcher(fileName);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }




    /**
     * 存 MySQL（供关键词检索）
     * <p>
     * ★ D46：同步写入过滤维度，供 fulltextSearchWithFilter 使用。
     * metadata 由 injectMetadata 和解析器共同填充，这里读出即可。
     */
    private void saveToMysql(List<Document> chunks, DocInfo docInfo) {
        List<RagChunk> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = chunk.getMetadata();

            RagChunk entity = new RagChunk();
            entity.setDocId(docInfo.docId());
            entity.setChunkIndex(i);
            entity.setSource(docInfo.originalName());
            entity.setContent(chunk.getText());
            entity.setFilePath(docInfo.storedName());

            entity.setDepartment((String) meta.get("department"));
            entity.setContentType((String) meta.get("content_type"));

            Object yearObj = meta.get("year");
            if (yearObj instanceof Number n) {
                entity.setYear(n.intValue());
            }

            // ★ D46 新增：密级和状态
            Object secObj = meta.get("security_level");
            if (secObj instanceof Number n) {
                entity.setSecurityLevel(n.intValue());
            }
            entity.setStatus((String) meta.get("status"));

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