package org.example.rag.ingest.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Filter;
import lombok.extern.slf4j.Slf4j;
import org.example.cache.service.SemanticCacheService;
import org.example.common.audit.AuditLogger;
import org.example.rag.ingest.config.IngestProperties;
import org.example.rag.ingest.entity.DocumentFingerprint;
import org.example.rag.ingest.mapper.DocumentFingerprintMapper;
import org.example.rag.shared.mapper.RagChunkMapper;
import org.example.rag.ingest.utils.FileHashUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.qdrant.client.ConditionFactory.matchKeyword;

/**
 * 增量更新服务
 * <p>
 * 扫描目录 → 对比指纹 → 新增/修改/删除
 */
@Slf4j
@Service
public class IncrementalUpdateService {

    private final AsyncIndexService asyncIndexService;
    private final SemanticCacheService semanticCacheService;
    private final IngestProperties ingestProperties;
    private final DocumentFingerprintMapper fingerprintMapper;
    private final DocumentIngestService ingestService;
    private final RagChunkMapper ragChunkMapper;
    private final QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:agent-rag}")
    private String qdrantCollection;

    public IncrementalUpdateService(AsyncIndexService asyncIndexService, SemanticCacheService semanticCacheService,
                                    IngestProperties ingestProperties,
                                    DocumentFingerprintMapper fingerprintMapper,
                                    DocumentIngestService ingestService,
                                    RagChunkMapper ragChunkMapper,
                                    QdrantClient qdrantClient) {
        this.asyncIndexService = asyncIndexService;
        this.semanticCacheService = semanticCacheService;
        this.ingestProperties = ingestProperties;
        this.fingerprintMapper = fingerprintMapper;
        this.ingestService = ingestService;
        this.ragChunkMapper = ragChunkMapper;
        this.qdrantClient = qdrantClient;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 扫描目录并执行增量更新
     */
    public void scanAndUpdate() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上次扫描尚未结束，跳过本次");
            return;
        }
        try {
            doScanAndUpdate();
        } finally {
            running.set(false);
        }
    }

    private void doScanAndUpdate() {
        String dirStr = ingestProperties.getListenFilesDir();
        Path dir = Paths.get(dirStr);

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            log.warn("监听目录不存在或不是目录: {}", dirStr);
            return;
        }

        log.info("开始扫描目录: {}", dir);

        try {
            Map<String, Path> currentFiles = scanFiles(dir);
            Map<String, DocumentFingerprint> indexedFiles = fingerprintMapper.findAll()
                    .stream()
                    .collect(Collectors.toMap(
                            DocumentFingerprint::getFilePath,
                            fp -> fp,
                            (a, b) -> a
                    ));

            // ★ D55：收集待入库文件
            List<Path> toIngest = new ArrayList<>();
            int updatedCount = 0;

            for (Map.Entry<String, Path> entry : currentFiles.entrySet()) {
                String filePath = entry.getKey();
                Path file = entry.getValue();

                DocumentFingerprint existing = indexedFiles.get(filePath);

                if (existing == null) {
                    toIngest.add(file);
                } else {
                    String currentHash = FileHashUtil.sha256(file);
                    if (!currentHash.equals(existing.getFileHash())) {
                        log.info("文件已修改: {}", filePath);
                        deleteFile(existing);
                        toIngest.add(file);
                        updatedCount++;
                    }
                }
            }

            // 已删除文件
            int deleted = 0;
            Set<String> currentPaths = currentFiles.keySet();
            for (Map.Entry<String, DocumentFingerprint> entry : indexedFiles.entrySet()) {
                if (!currentPaths.contains(entry.getKey())) {
                    log.info("文件已删除: {}", entry.getKey());
                    deleteFile(entry.getValue());
                    deleted++;
                }
            }

            // ★ D55：批量异步入库
            int ingested = 0;
            int failed = 0;
            if (!toIngest.isEmpty()) {
                log.info("[D55] 提交 {} 个文件到异步索引服务", toIngest.size());
                AsyncIndexService.BatchResult result = asyncIndexService.ingestBatch(toIngest);
                ingested = result.success();
                failed = result.failed();
                log.info("[D55] 异步索引完成: {}", result.summary());
            }

            log.info("增量更新完成：新增/更新 {}，失败 {}，删除 {}",
                    ingested, failed, deleted);

        } catch (Exception e) {
            log.error("增量更新失败", e);
        }
    }

    private Map<String, Path> scanFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .collect(Collectors.toMap(
                            p -> p.toAbsolutePath().toString(),
                            p -> p,
                            (a, b) -> a
                    ));
        }
    }

    private boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf")
                || name.endsWith(".docx")
                || name.endsWith(".doc")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".xlsx")   // D44 表格新增
                || name.endsWith(".xls")
                || name.endsWith(".csv")
                || name.endsWith(".jpg")    // ★ D45 图片新增
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".bmp")
                || name.endsWith(".tiff")
                || name.endsWith(".tif");
    }

    /**
     * 入库单个文件
     */
    private boolean ingestFile(Path file) {
        try {
            String filePath = file.toAbsolutePath().toString();

            // ★ 幂等检查：已经入库就跳过
            DocumentFingerprint existing = fingerprintMapper.findByFilePath(filePath);
            if (existing != null) {
                log.info("文件已存在指纹记录，跳过入库: {}", filePath);
                return false;
            }

            String hash = FileHashUtil.sha256(file);
            log.info("入库文件: {}", filePath);

            DocumentIngestService.DocInfo docInfo = ingestService.ingest(new FileSystemResource(file));

            DocumentFingerprint fp = new DocumentFingerprint();
            fp.setFilePath(filePath);
            fp.setFileHash(hash);
            fp.setDocId(docInfo.docId());
            fp.setSource(docInfo.originalName());
            fp.setFileSize(Files.size(file));
            fp.setLastModified(LocalDateTime.now());
            fp.setIndexedAt(LocalDateTime.now());

            try {
                fingerprintMapper.insert(fp);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // ★ 插入时冲突——说明另一个线程抢先了——跳过
                log.warn("指纹记录已存在（并发），跳过: {}", filePath);
                return false;
            }

            log.info("入库成功: {} (docId={})", filePath, docInfo.docId());

            // ★ 入库成功后清缓存
            semanticCacheService.clearAll();

            return true;

        } catch (Exception e) {
            log.error("入库失败: {}", file, e);
            return false;
        }
    }

    /**
     * 删除文件的所有关联数据
     */
    private void deleteFile(DocumentFingerprint fp) {
        String docId = fp.getDocId();
        log.info("========== 开始删除文件数据 ==========");
        log.info("filePath: {}", fp.getFilePath());
        log.info("docId: {}", docId);

        if (docId == null || docId.isBlank()) {
            log.warn("指纹没有 docId，跳过: {}", fp.getFilePath());
            fingerprintMapper.deleteByFilePath(fp.getFilePath());
            return;
        }

        // ① 删 MySQL rag_chunks
        try {
            int rows = ragChunkMapper.deleteByDocId(docId);
            log.info("✅ rag_chunks 删除 {} 行 (docId={})", rows, docId);
        } catch (Exception e) {
            log.error("❌ 删 rag_chunks 失败: docId={}", docId, e);
        }

        // ② 删 Qdrant
        try {
            long before = qdrantClient.countAsync(qdrantCollection).get();
            Filter filter = Filter.newBuilder()
                    .addMust(matchKeyword("doc_id", docId))
                    .build();
            Points.UpdateResult result = qdrantClient
                    .deleteAsync(qdrantCollection, filter)
                    .get();
            long after = qdrantClient.countAsync(qdrantCollection).get();
            log.info("✅ Qdrant 删除 {} 个 points (docId={}, status={})",
                    before - after, docId, result.getStatus());
        } catch (Exception e) {
            log.error("❌ 删 Qdrant 失败: docId={}", docId, e);
        }

        // ③ ★ 删磁盘文件（rag-files/{docId}.docx）
        try {
            deleteStoredFile(docId);
        } catch (Exception e) {
            log.error("❌ 删磁盘文件失败: docId={}", docId, e);
        }

        // ④ 删指纹记录
        try {
            int rows = fingerprintMapper.deleteByDocId(docId);
            log.info("✅ rag_documents 删除 {} 行 (docId={})", rows, docId);
        } catch (Exception e) {
            log.error("❌ 删指纹记录失败: docId={}", docId, e);
        }

        log.info("========== 删除完成 ==========");

        // ★ 删除完成后清缓存
        try {
            semanticCacheService.clearAll();
            log.info("✅ 文档更新，语义缓存已清除");
        } catch (Exception e) {
            log.warn("清缓存失败", e);
        }

        // ★ 审计
        AuditLogger.docDelete(
                fp.getFilePath() != null ? extractTenantFromPath(fp.getFilePath()) : "unknown",
                "system", docId, fp.getSource());
    }

    private String extractTenantFromPath(String filePath) {
        try {
            Path base = Path.of(ingestProperties.getListenFilesDir()).toAbsolutePath().normalize();
            Path file = Path.of(filePath).toAbsolutePath().normalize();
            Path relative = base.relativize(file);
            if (relative.getNameCount() >= 1) {
                return relative.getName(0).toString();
            }
        } catch (Exception ignore) {}
        return "unknown";
    }

    /**
     * 删除磁盘上存储的文件副本
     * 文件名格式：{docId}.{ext}
     */
    private void deleteStoredFile(String docId) throws IOException {
        Path storageDir = Path.of(ingestProperties.getFileStorageDir());
        if (!Files.exists(storageDir)) {
            return;
        }

        // ★ 匹配 {docId}.* 的文件（不管扩展名）
        try (Stream<Path> stream = Files.list(storageDir)) {
            List<Path> targets = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(docId + ".");
                    })
                    .toList();

            if (targets.isEmpty()) {
                log.warn("⚠️ 磁盘上未找到文件副本: docId={}", docId);
                return;
            }

            for (Path target : targets) {
                Files.deleteIfExists(target);
                log.info("✅ 磁盘文件已删除: {}", target);
            }
        }
    }
}