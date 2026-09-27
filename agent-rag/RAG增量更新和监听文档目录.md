# RAG增量更新：监听文档目录，自动重新索引

> 实现文档目录的自动扫描，支持新增 / 修改 / 删除，四处数据（Qdrant + MySQL chunks + 指纹表 + 磁盘副本）保持同步

---

## 一、整体设计

```
每 60 秒扫描一次 D:/download/testVectorData
    ↓
对比目录文件 vs 数据库指纹
    ↓
┌─ 新文件          → 4 处写入
├─ 文件改了（哈希不同）→ 4 处删旧 + 4 处入新
└─ 文件删了        → 4 处同步删除
```

**四处数据**：

| 数据源 | 存什么 | 谁维护 |
|--------|--------|--------|
| **Qdrant** | 向量 | VectorStore |
| **rag_chunks（MySQL）** | chunk 原文 | RagChunkMapper |
| **rag_documents（MySQL）** | 文件指纹 | DocumentFingerprintMapper |
| **rag-files（磁盘）** | 文件副本 | DocumentIngestService |

---

## 二、MySQL 建表

```sql
USE agent_rag;

CREATE TABLE IF NOT EXISTS rag_documents (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_path     VARCHAR(512) NOT NULL,
    file_hash     VARCHAR(64)  NOT NULL,
    doc_id        VARCHAR(64)  NOT NULL,
    source        VARCHAR(255),
    file_size     BIGINT,
    last_modified TIMESTAMP,
    indexed_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_file_path (file_path),
    INDEX idx_doc_id (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 三、实体类 `DocumentFingerprint`

`agent-rag/src/main/java/org/example/rag/entity/DocumentFingerprint.java`：

```java
package org.example.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentFingerprint {
    private Long id;
    private String filePath;
    private String fileHash;
    private String docId;
    private String source;
    private Long fileSize;
    private LocalDateTime lastModified;
    private LocalDateTime indexedAt;
}
```

---

## 四、Mapper 接口 `DocumentFingerprintMapper`

`agent-rag/src/main/java/org/example/rag/mapper/DocumentFingerprintMapper.java`：

```java
package org.example.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.rag.entity.DocumentFingerprint;

import java.util.List;

@Mapper
public interface DocumentFingerprintMapper {

    DocumentFingerprint findByFilePath(@Param("filePath") String filePath);

    List<DocumentFingerprint> findAll();

    int insert(DocumentFingerprint fp);

    int update(DocumentFingerprint fp);

    int deleteByDocId(@Param("docId") String docId);

    int deleteByFilePath(@Param("filePath") String filePath);

    long count();
}
```

---

## 五、Mapper XML `DocumentFingerprintMapper.xml`

`agent-rag/src/main/resources/mapper/DocumentFingerprintMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.example.rag.mapper.DocumentFingerprintMapper">

    <resultMap id="BaseResultMap" type="org.example.rag.entity.DocumentFingerprint">
        <id     column="id"            property="id"/>
        <result column="file_path"     property="filePath"/>
        <result column="file_hash"     property="fileHash"/>
        <result column="doc_id"        property="docId"/>
        <result column="source"        property="source"/>
        <result column="file_size"     property="fileSize"/>
        <result column="last_modified" property="lastModified"/>
        <result column="indexed_at"    property="indexedAt"/>
    </resultMap>

    <select id="findByFilePath" resultMap="BaseResultMap">
        SELECT * FROM rag_documents WHERE file_path = #{filePath}
    </select>

    <select id="findAll" resultMap="BaseResultMap">
        SELECT * FROM rag_documents
    </select>

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO rag_documents
            (file_path, file_hash, doc_id, source, file_size, last_modified, indexed_at)
        VALUES
            (#{filePath}, #{fileHash}, #{docId}, #{source},
             #{fileSize}, #{lastModified}, #{indexedAt})
    </insert>

    <update id="update">
        UPDATE rag_documents
        SET file_hash = #{fileHash},
            doc_id = #{docId},
            source = #{source},
            file_size = #{fileSize},
            last_modified = #{lastModified},
            indexed_at = #{indexedAt}
        WHERE file_path = #{filePath}
    </update>

    <delete id="deleteByDocId">
        DELETE FROM rag_documents WHERE doc_id = #{docId}
    </delete>

    <delete id="deleteByFilePath">
        DELETE FROM rag_documents WHERE file_path = #{filePath}
    </delete>

    <select id="count" resultType="long">
        SELECT COUNT(*) FROM rag_documents
    </select>

</mapper>
```

**同时给 `RagChunkMapper` 加删除方法**：

```java
// 接口
int deleteByDocId(@Param("docId") String docId);
```

```xml
<!-- RagChunkMapper.xml -->
<delete id="deleteByDocId">
    DELETE FROM rag_chunks WHERE doc_id = #{docId}
</delete>
```

---

## 六、文件哈希工具 `FileHashUtil`

`agent-rag/src/main/java/org/example/rag/util/FileHashUtil.java`：

```java
package org.example.rag.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public class FileHashUtil {

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("计算文件哈希失败: " + file, e);
        }
    }
}
```

---

## 七、配置类 `RagProperties`（补充字段）

```java
/** 监听文件目录 */
private String listenFilesDir = "./data/testVectorData/";

/** 扫描时间间隔（毫秒） */
private Integer scanIntervalMs = 60000;

/** 是否启用增量更新 */
private Boolean incrementalEnabled = true;
```

`application.yml`：

```yaml
app:
  rag:
    file-storage-dir: D:/DevelopmentTool/qdrant/rag-files/
    listen-files-dir: D:/download/testVectorData/
    scan-interval-ms: 60000
    incremental-enabled: true
    top-k: 5
    similarity-threshold: 0.5
    rrf-k: 60
    recall-multiplier: 2
    rerank:
      api-url: http://localhost:8081/rerank
      timeout-seconds: 60
      min-score: 0.3
```

---

## 八、`DocumentIngestService` 改造

**关键改动**：`ingestWithTika` / `ingestPdf` 返回 `DocInfo`，`DocInfo` 改成 `public`。

```java
public DocInfo ingestWithTika(Resource resource) {
    DocInfo docInfo = saveFile(resource);
    List<Document> documents = parseService.parseWithTika(resource);
    List<Document> chunks = split(documents);
    injectMetadata(chunks, docInfo);

    vectorStore.add(chunks);
    saveToMysql(chunks, docInfo);

    log.info("Tika 入库完成，docId={}, source={}, 切片数={}",
            docInfo.docId(), docInfo.originalName(), chunks.size());

    return docInfo;   // ★ 返回完整信息
}

public DocInfo ingestPdf(Resource resource) {
    // 同上逻辑
    return docInfo;
}

// ★ 改成 public
public record DocInfo(String docId, String originalName, String storedName) {}
```

**调用方同步改**：

```java
// 旧
int chunks = ingestService.ingestWithTika(new FileSystemResource(filePath));

// 新
DocumentIngestService.DocInfo info = ingestService.ingestWithTika(new FileSystemResource(filePath));
```

---

## 九、核心服务 `IncrementalUpdateService`

`agent-rag/src/main/java/org/example/rag/service/IncrementalUpdateService.java`：

```java
package org.example.rag.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Filter;
import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.RagProperties;
import org.example.rag.entity.DocumentFingerprint;
import org.example.rag.mapper.DocumentFingerprintMapper;
import org.example.rag.mapper.RagChunkMapper;
import org.example.rag.util.FileHashUtil;
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

@Slf4j
@Service
public class IncrementalUpdateService {

    private final RagProperties ragProperties;
    private final DocumentFingerprintMapper fingerprintMapper;
    private final DocumentIngestService ingestService;
    private final RagChunkMapper ragChunkMapper;
    private final QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:agent-rag}")
    private String qdrantCollection;

    /** 防并发扫描 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IncrementalUpdateService(RagProperties ragProperties,
                                    DocumentFingerprintMapper fingerprintMapper,
                                    DocumentIngestService ingestService,
                                    RagChunkMapper ragChunkMapper,
                                    QdrantClient qdrantClient) {
        this.ragProperties = ragProperties;
        this.fingerprintMapper = fingerprintMapper;
        this.ingestService = ingestService;
        this.ragChunkMapper = ragChunkMapper;
        this.qdrantClient = qdrantClient;
    }

    // ==================== 主入口 ====================

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
        String dirStr = ragProperties.getListenFilesDir();
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

            int added = 0, updated = 0, deleted = 0;

            // 处理新增 / 修改
            for (Map.Entry<String, Path> entry : currentFiles.entrySet()) {
                String filePath = entry.getKey();
                Path file = entry.getValue();

                DocumentFingerprint existing = indexedFiles.get(filePath);

                if (existing == null) {
                    if (ingestFile(file)) {
                        added++;
                        sleepBetweenFiles();
                    }
                } else {
                    String currentHash = FileHashUtil.sha256(file);
                    if (!currentHash.equals(existing.getFileHash())) {
                        log.info("文件已修改: {}", filePath);
                        deleteFile(existing);
                        if (ingestFile(file)) {
                            updated++;
                            sleepBetweenFiles();
                        }
                    }
                }
            }

            // 处理删除
            Set<String> currentPaths = currentFiles.keySet();
            for (Map.Entry<String, DocumentFingerprint> entry : indexedFiles.entrySet()) {
                if (!currentPaths.contains(entry.getKey())) {
                    log.info("文件已删除: {}", entry.getKey());
                    deleteFile(entry.getValue());
                    deleted++;
                }
            }

            log.info("增量更新完成：新增 {}，修改 {}，删除 {}", added, updated, deleted);

        } catch (Exception e) {
            log.error("增量更新失败", e);
        }
    }

    // ==================== 文件扫描 ====================

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
                || name.endsWith(".md");
    }

    private void sleepBetweenFiles() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 入库 ====================

    private boolean ingestFile(Path file) {
        try {
            String filePath = file.toAbsolutePath().toString();

            // 幂等检查
            DocumentFingerprint existing = fingerprintMapper.findByFilePath(filePath);
            if (existing != null) {
                log.info("文件已存在指纹记录，跳过入库: {}", filePath);
                return false;
            }

            String hash = FileHashUtil.sha256(file);
            log.info("入库文件: {}", filePath);

            DocumentIngestService.DocInfo docInfo =
                    ingestService.ingestWithTika(new FileSystemResource(file));

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
                log.warn("指纹记录已存在（并发），跳过: {}", filePath);
                return false;
            }

            log.info("入库成功: {} (docId={})", filePath, docInfo.docId());
            return true;

        } catch (Exception e) {
            log.error("入库失败: {}", file, e);
            return false;
        }
    }

    // ==================== 删除 ====================

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

        // ② 删 Qdrant（Filter 按 payload.doc_id）
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

        // ③ 删磁盘文件副本
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
    }

    /**
     * 删除磁盘上存储的文件副本（{docId}.{ext}）
     */
    private void deleteStoredFile(String docId) throws IOException {
        Path storageDir = Path.of(ragProperties.getFileStorageDir());
        if (!Files.exists(storageDir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(storageDir)) {
            List<Path> targets = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(docId + "."))
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
```

---

## 十、定时任务 `ScheduledConfig`

`agent-rag/src/main/java/org/example/rag/config/ScheduledConfig.java`：

```java
package org.example.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.IncrementalUpdateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.rag.incremental-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ScheduledConfig {

    private final IncrementalUpdateService incrementalUpdateService;

    public ScheduledConfig(IncrementalUpdateService incrementalUpdateService) {
        this.incrementalUpdateService = incrementalUpdateService;
    }

    @Scheduled(
            initialDelayString = "${app.rag.scan-interval-ms:60000}",   // ★ 首次延迟
            fixedDelayString = "${app.rag.scan-interval-ms:60000}"
    )
    public void scanDirectory() {
        try {
            incrementalUpdateService.scanAndUpdate();
        } catch (Exception e) {
            log.error("定时扫描失败", e);
        }
    }
}
```

---

## 十一、启动扫描 `RagStartupRunner`

`agent-rag/src/main/java/org/example/rag/config/RagStartupRunner.java`：

```java
package org.example.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.IncrementalUpdateService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@Order(100)
public class RagStartupRunner implements ApplicationRunner {

    private final IncrementalUpdateService incrementalUpdateService;

    public RagStartupRunner(IncrementalUpdateService incrementalUpdateService) {
        this.incrementalUpdateService = incrementalUpdateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 应用启动——首次扫描文档目录 ==========");
        try {
            incrementalUpdateService.scanAndUpdate();
        } catch (Exception e) {
            log.error("启动扫描失败", e);
        }
    }
}
```

---

## 十二、重试配置

**启动类加 `@EnableRetry`**：

```java
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class FirstAgentProjectApplication { ... }
```

**`DocumentIngestService` 加 `@Retryable`**：

```java
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

@Retryable(
        retryFor = {
                org.springframework.web.reactive.function.client.WebClientResponseException.class,
                java.net.SocketException.class,
                java.io.IOException.class
        },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
)
public DocInfo ingestWithTika(Resource resource) { ... }
```

**依赖**（`agent-api/pom.xml` 必须显式加）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
```

---

## 十三、踩过的坑

| # | 坑 | 原因 | 解决 |
|:---:|------|------|------|
| 1 | **`rag_chunks` 插入 2 次** | `RagStartupRunner` 和 `@Scheduled` 启动时同时扫描 | `@Scheduled` 加 `initialDelay` + `AtomicBoolean` 锁 |
| 2 | **`Duplicate entry 'file_path'`** | 同一文件重复入库 | `ingestFile` 先 `findByFilePath` 幂等检查 + try-catch `DuplicateKeyException` |
| 3 | **`Connection reset`** | 阿里云 embedding 接口限流 | 文件间 `Thread.sleep(1000)` + `@Retryable` |
| 4 | **`ClassNotFoundException: aspectj`** | AOP 依赖没传到运行时 | `agent-api/pom.xml` 显式加 `spring-boot-starter-aop` |
| 5 | **Qdrant 数据删不掉** | `vectorStore.delete(List.of(docId))` 删的是 Point ID，不是 `payload.doc_id` | 改用 Qdrant Filter + `matchKeyword("doc_id", docId)` |
| 6 | **`rag-files` 磁盘残留** | 删除逻辑漏了磁盘副本 | 加 `deleteStoredFile(docId)` |
| 7 | **`deleteAsync().get()` 返回值** | 是 `Points.UpdateResult`，不是 `long` | 用前后 `count` 相减获得删除数 |

---

## 十四、验证

### 1. 启动日志

```
========== 应用启动——首次扫描文档目录 ==========
开始扫描目录: D:\download\testVectorData
入库文件: D:\download\testVectorData\文档1.docx
原文件已保存: ...（原名：文档1.docx）
Tika 切片完成，chunk 数: N
MySQL 同步完成，N 条
入库成功: ... (docId=xxx)
增量更新完成：新增 3，修改 0，删除 0
```

### 2. 删除日志

```
文件已删除: D:\download\testVectorData\婚姻.txt
========== 开始删除文件数据 ==========
filePath: D:\download\testVectorData\婚姻.txt
docId: 94a0433e-5a94-4c87-82ff-ce5cfdc9fcf8
✅ rag_chunks 删除 5 行 (docId=94a0433e-...)
✅ Qdrant 删除 5 个 points (docId=94a0433e-..., status=Completed)
✅ 磁盘文件已删除: D:\DevelopmentTool\qdrant\rag-files\94a0433e-....txt
✅ rag_documents 删除 1 行 (docId=94a0433e-...)
========== 删除完成 ==========
```

### 3. 数据一致性验证

```sql
USE agent_rag;

-- 三处数据应该一致
SELECT COUNT(*) FROM rag_documents;
SELECT COUNT(DISTINCT doc_id) FROM rag_chunks;
```

```powershell
# Qdrant 数据量
curl.exe http://localhost:6333/collections/agent-rag

# 磁盘文件数
dir "D:\DevelopmentTool\qdrant\rag-files\"
```

**四个数字应该完全对应**。

---

## 十五、一句话总结

> **增量更新核心**：
> 1. **指纹表 `rag_documents`** —— 记录文件的 SHA-256 哈希
> 2. **`AtomicBoolean` 锁 + `@Scheduled initialDelay`** —— 防并发
> 3. **`@Retryable` + `sleep`** —— 防网络抖动
> 4. **Qdrant Filter 删除** —— 按 `payload.doc_id` 精确删
> 5. **四处同步**：Qdrant + rag_chunks + rag_documents + rag-files