# RAG 增量更新：监听文档目录，自动重新索引

> 实现文档目录的自动扫描，支持新增 / 修改 / 删除，四处数据（Qdrant + MySQL chunks + 指纹表 + 磁盘副本）保持同步
>
> **D55 后**：串行入库改造为异步批量入库，大幅提升大批量导入性能

---

## 一、整体设计

### 1.1 扫描流程

```
每 5 分钟扫描一次 D:/download/testVectorData
    ↓
对比目录文件 vs 数据库指纹
    ↓
┌─ 新文件             → 收集到 toIngest
├─ 文件改了（哈希不同）→ 删旧 + 收集到 toIngest
└─ 文件删了           → 四处同步删除
    ↓
toIngest 非空 → AsyncIndexService.ingestBatch
    ↓
有界线程池（4 并发）+ 信号量限流
    ↓
每个文件：DocumentIngestService.ingestWithFingerprint
    ↓
（幂等检查 → 算 hash → 解析 → 切片 → 注入元数据 →
  向量化 → 存 MySQL → 写指纹）
```

### 1.2 四处数据

| 数据源 | 存什么 | 谁维护 |
|--------|--------|--------|
| **Qdrant** | 向量 + payload | VectorStore |
| **rag_chunks（MySQL）** | chunk 原文 + 元数据 | RagChunkMapper |
| **rag_documents（MySQL）** | 文件指纹 | DocumentFingerprintMapper |
| **rag-files（磁盘）** | 文件副本 | DocumentIngestService |

**四种状态变化**：

| 事件 | Qdrant | rag_chunks | rag_documents | rag-files |
|------|:---:|:---:|:---:|:---:|
| 新增 | 写入 | 写入 | 写入 | 写入 |
| 修改 | 删旧 + 写新 | 删旧 + 写新 | 更新 | 覆盖 |
| 删除 | 按 doc_id 删 | 按 doc_id 删 | 按 doc_id 删 | 按 docId.* 删 |

---

## 二、数据模型

### 2.1 `rag_documents`（指纹表）

| 字段 | 说明 |
|------|------|
| `id` | 主键 |
| `file_path` | 文件绝对路径（**UNIQUE**） |
| `file_hash` | SHA-256 哈希 |
| `doc_id` | 对应的文档 ID |
| `source` | 原文件名 |
| `file_size` | 文件大小 |
| `last_modified` | 文件修改时间 |
| `indexed_at` | 入库时间 |

**唯一索引**：`file_path` —— 防重复入库。

### 2.2 `rag_chunks`（关键词检索）

| 字段 | 说明 |
|------|------|
| `tenant_id` | D54：租户隔离 |
| `doc_id` | 文档 ID |
| `chunk_index` | 切片序号 |
| `content` | 切片原文（**FULLTEXT ngram**） |
| `department` / `year` / `content_type` | D46：业务过滤维度 |
| `security_level` / `status` | D46：权限维度 |
| `page_number` / `total_chunks` | D50：页码与总切片数 |

---

## 三、包结构（D55 重构后）

```
org.example.rag.ingest
  ├── service/
  │   ├── DocumentIngestService       ← 入库原子操作（含 ingestWithFingerprint）
  │   ├── DocumentParseService        ← 解析分发
  │   ├── TableExtractService         ← Excel/CSV
  │   ├── ImageDocumentReader         ← 图片
  │   ├── OcrService                  ← OCR
  │   ├── IncrementalUpdateService    ← 目录扫描
  │   └── AsyncIndexService           ← 异步批量调度
  ├── entity/    DocumentFingerprint
  ├── mapper/    DocumentFingerprintMapper
  ├── config/
  │   ├── IngestProperties            ← 摄取配置（原 RagProperties 拆分）
  │   ├── AsyncIndexProperties        ← 异步配置
  │   ├── AsyncIndexConfig            ← 线程池
  │   ├── OcrProperties
  │   ├── RagStartupRunner
  │   └── ScheduledConfig
  └── util/      FileHashUtil
```

---

## 四、配置分离（D55）

**原 `RagProperties` 拆成两块**：

| 配置类 | 前缀 | 管什么 |
|--------|------|--------|
| `IngestProperties` | `app.rag.ingest.*` | 目录、扫描间隔、是否启用增量 |
| `AsyncIndexProperties` | `app.async-index.*` | 并发、限流、超时、批大小 |

### 4.1 `IngestProperties` 关键字段

| 字段 | 默认值 | 说明 |
|------|:---:|------|
| `fileStorageDir` | `./data/rag-files/` | 文件副本存储目录 |
| `listenFilesDir` | `./data/testVectorData/` | 监听目录 |
| `scanIntervalMs` | `60000` | 扫描间隔（D55 后改为 `300000`） |
| `incrementalEnabled` | `true` | 是否启用定时扫描 |

### 4.2 `AsyncIndexProperties` 关键字段

| 字段 | 默认值 | 说明 |
|------|:---:|------|
| `enabled` | `true` | 关掉退化为同步 |
| `concurrency` | `4` | 并发文件数——**不能太大防 API 限流** |
| `queueCapacity` | `500` | 有界队列——背压 |
| `perFileTimeoutSeconds` | `300` | 单文件超时 |
| `minIntervalMs` | `200` | 文件间最小间隔——防 429 |
| `progressLogInterval` | `10` | 每 N 个文件打一次进度 |
| `embeddingBatchSize` | `10` | **阿里云 DashScope 硬限制** |

### 4.3 `application.yml`

```yaml
app:
  rag:
    ingest:
      file-storage-dir: D:/DevelopmentTool/qdrant/rag-files/
      listen-files-dir: D:/download/testVectorData
      scan-interval-ms: 300000
      incremental-enabled: true
    retrieval:
      top-k: 2
      similarity-threshold: 0.5
      rrf-k: 60
      recall-multiplier: 2
      rerank:
        api-url: http://localhost:8081/rerank
        timeout-seconds: 8
        min-score: 0.3

  async-index:
    enabled: true
    concurrency: 4
    queue-capacity: 500
    per-file-timeout-seconds: 300
    min-interval-ms: 200
    progress-log-interval: 10
    embedding-batch-size: 10
```

---

## 五、核心方法职责

### 5.1 `DocumentIngestService.ingestWithFingerprint(Path)`

**职责**：一次**幂等入库**——把"检查 + 入库 + 记账"封装成一个原子操作。

**执行步骤**：

1. 查指纹 —— 已存在则**跳过**（幂等）
2. 算 SHA-256
3. 调 `ingest(Resource)` —— 解析、切片、向量化、存 MySQL
4. 写指纹 —— `rag_documents` 表
5. 并发冲突时捕获 `DuplicateKeyException` 兜底

**为什么放这里**（而不是 `IncrementalUpdateService`）：

- `docId` 是 `ingest` 的产物 —— 只有它知道"入库成功了什么"
- 指纹 = 入库成功的"收据" —— 收据该由"经手人"打
- **将来加 HTTP 上传 / S3 入口时，指纹逻辑自动复用** —— 不用重复写

### 5.2 `AsyncIndexService.ingestBatch(List<Path>)`

**职责**：把一批文件**并发提交**给线程池，等待全部完成，返回汇总。

**关键设计**：

| 点 | 做法 |
|----|------|
| **有界并发** | 线程池 `maxPoolSize = concurrency` |
| **背压** | 有界队列 + `CallerRunsPolicy` —— 队列满时生产者阻塞 |
| **限流** | `Semaphore` —— 限制 embedding API 并发 |
| **失败隔离** | 每个文件独立 `CompletableFuture` |
| **进度日志** | 每 N 个文件打一次 |
| **优雅关闭** | `waitForTasksToCompleteOnShutdown(true)` |

### 5.3 `IncrementalUpdateService.doScanAndUpdate()`

**职责**：扫描目录 → 对比指纹 → 收集差异 → 提交异步。

**关键步骤**：

1. `Files.walk(dir)` 扫描支持的文件
2. `fingerprintMapper.findAll()` 拉全部指纹
3. **差异比对**：
    - 新增（指纹没有） → 加入 `toIngest`
    - 修改（hash 不同） → 删旧 + 加入 `toIngest`
    - 删除（指纹有但目录没有） → **同步删除**
4. `asyncIndexService.ingestBatch(toIngest)`
5. 汇总日志：`新增/更新 X，失败 Y，删除 Z`

### 5.4 `deleteFile(DocumentFingerprint)`

**职责**：四处同步删除。

| 步骤 | 目标 | 方式 |
|:---:|------|------|
| ① | MySQL `rag_chunks` | `deleteByDocId` |
| ② | Qdrant | Filter `matchKeyword("doc_id", docId)` |
| ③ | 磁盘 `rag-files` | 匹配 `{docId}.*` 删除 |
| ④ | MySQL `rag_documents` | `deleteByDocId` |

**顺序很重要**：先删"业务数据"，最后删"指纹" —— 如果中途失败，指纹还在，下次扫描会重试。

---

## 六、Embedding 分批（关键）

**阿里云 DashScope `text-embedding-v4` 单批 ≤ 10 条** —— 一份文档切片常几十条 —— 必须分批。

### 6.1 错误示例

```java
vectorStore.add(chunks);   // ❌ chunks.size() > 10 → HTTP 400
```

**报错**：

```
batch size is invalid, it should not be larger than 10.: input.contents
```

### 6.2 正确做法

```java
private static final int EMBEDDING_BATCH_SIZE = 10;

private void addToVectorStoreInBatches(List<Document> chunks) {
    for (int i = 0; i < chunks.size(); i += 10) {
        int end = Math.min(i + 10, chunks.size());
        vectorStore.add(chunks.subList(i, end));
    }
}
```

**日志输出**：

```
[D55] 向量化批次 1/5 完成（本批 10 条）
[D55] 向量化批次 2/5 完成（本批 10 条）
...
[D55] 向量化批次 5/5 完成（本批 3 条）
```

### 6.3 批大小的取舍

| 批大小 | 影响 |
|:---:|------|
| **太小（1-2）** | API 调用多，网络往返慢 |
| **10（阿里云上限）** | 最优 —— 单次尽可能多 |
| **> 10** | HTTP 400 报错 |

**换供应商时**——OpenAI 允许 100 条 / 批 —— **配置外置**到 yml。

---

## 七、异步索引执行流程

```
IncrementalUpdateService.doScanAndUpdate
    ↓ 收集 toIngest（List<Path>）
AsyncIndexService.ingestBatch(toIngest)
    ↓ 提交 N 个 CompletableFuture 到线程池
┌────────────────────────────────────────┐
│  async-index-1   async-index-2  ...   │   ← 4 个 worker 并发
│    ├─ Semaphore.acquire()              │   ← 限流
│    ├─ Thread.sleep(200ms)              │   ← 防 429
│    ├─ ingestWithFingerprint(file)      │   ← 幂等入库
│    └─ Semaphore.release()              │
└────────────────────────────────────────┘
    ↓ CompletableFuture.allOf(...).join()
返回 BatchResult(total, success, failed, failedFiles)
```

### 7.1 并发数怎么选

| concurrency | 场景 |
|:---:|------|
| **1** | 无异步收益（退化为串行） |
| **4** | 推荐起点 —— 3-4 倍提速 |
| **8+** | 容易触发 embedding API 429 |

**观察**：日志里出现 429 → 降到 2 或加大 `minIntervalMs`。

### 7.2 吞吐预估

| 场景 | 50 个文件耗时 |
|------|:---:|
| 同步串行 | 约 9 分钟 |
| **异步 4 并发** | **约 2.5 分钟** |

**提速 3-4 倍**。

---

## 八、定时任务 & 启动扫描

### 8.1 `ScheduledConfig`

- `@EnableScheduling` 开启定时
- `@ConditionalOnProperty(app.rag.ingest.incremental-enabled=true)` 可开关
- `@Scheduled(initialDelay, fixedDelay)` —— **首次延迟 = 扫描间隔**（避免启动时和 `RagStartupRunner` 撞车）
- `AtomicBoolean` 锁 —— 防上一次扫描未完

### 8.2 `RagStartupRunner`

- 实现 `ApplicationRunner`
- **异步线程** —— `new Thread(...).start()` —— 不阻塞应用启动
- **延迟 15 秒** —— 等依赖服务（Qdrant / MySQL / Redis）就绪
- `@Order(100)` —— 让其他 Runner 先跑

### 8.3 为什么两者并存

| 场景 | 谁触发 |
|------|--------|
| 应用启动后首次扫描 | `RagStartupRunner`（延迟 15 秒） |
| 后续每次扫描 | `ScheduledConfig`（每 5 分钟） |

**两者都有 `AtomicBoolean` 保护** —— 不会并发跑。

---

## 九、重试与限流

### 9.1 `@Retryable`

**加在 `DocumentIngestService.ingest` 上**：

- `maxAttempts = 3` —— 最多 3 次
- `backoff = @Backoff(delay=1000, multiplier=2)` —— 1s → 2s → 4s
- **只对可重试异常** —— 网络抖动 / 429 / 连接重置

### 9.2 依赖

`agent-api/pom.xml` 必须显式加：

- `spring-boot-starter-aop` —— `@Retryable` 基于 AOP
- `spring-retry`

**否则** —— `ClassNotFoundException: aspectj` —— 运行时报错。

### 9.3 限流双层

| 层 | 手段 | 作用 |
|---|------|------|
| 应用层 | `Semaphore` + `minIntervalMs` | 控制 embedding API 并发 |
| 网络层 | `@Retryable` | 429 时退避重试 |

---

## 十、踩过的坑（9 条）

| # | 现象 | 原因 | 解决 |
|:---:|------|------|------|
| 1 | `rag_chunks` 插入 2 次 | `RagStartupRunner` 和 `@Scheduled` 启动时同时扫描 | `@Scheduled` 加 `initialDelay` + `AtomicBoolean` 锁 |
| 2 | `Duplicate entry 'file_path'` | 同一文件重复入库 | `ingestWithFingerprint` 先 `findByFilePath` 幂等 + try-catch `DuplicateKeyException` |
| 3 | `Connection reset` | 阿里云 embedding 限流 | `Semaphore` + `minIntervalMs` + `@Retryable` |
| 4 | `ClassNotFoundException: aspectj` | AOP 依赖没传到运行时 | `agent-api/pom.xml` 显式加 `spring-boot-starter-aop` |
| 5 | Qdrant 数据删不掉 | `vectorStore.delete(List.of(docId))` 删的是 Point ID，不是 payload 里的 `doc_id` | 用 Qdrant Filter + `matchKeyword("doc_id", docId)` |
| 6 | `rag-files` 磁盘残留 | 删除逻辑漏了磁盘副本 | 加 `deleteStoredFile(docId)` |
| 7 | `deleteAsync().get()` 返回值类型不对 | 是 `Points.UpdateResult`，不是 `long` | 用前后 `count()` 相减获得删除数 |
| 8 | `batch size is invalid, it should not be larger than 10` | 阿里云 embedding 单批上限 10 条 | `addToVectorStoreInBatches` 分批 |
| 9 | `rag_documents` 表为空 | D55 异步化后没搬"写指纹"逻辑 | `DocumentIngestService.ingestWithFingerprint` 统一处理 |

---

## 十一、验证

### 11.1 启动日志

```
RagStartupRunner 已注册，将在 15 秒后异步扫描
[D55] 异步索引线程池初始化: concurrency=4, queueCapacity=500
[D55] AsyncIndexService: enabled=true, concurrency=4, queueCapacity=500

（15 秒后）
========== 启动后延迟扫描文档目录 ==========
开始扫描目录: D:\download\testVectorData
[D55] 提交 5 个文件到异步索引服务
[D55] 批量异步入库开始: 文件数=5, 并发=4
[D55] 向量化批次 1/5 完成（本批 10 条）
[D55] 指纹已写入: docId=xxx, file=文档1.docx
...
[D55] 进度: 5/5 (100%), 成功=5, 失败=0, 耗时=16057ms, 速率=0.31/s
[D55] 批量异步入库完成: 总数=5, 成功=5, 失败=0, 总耗时=16058ms
[D55] 异步索引完成: 总数 5, 成功 5, 失败 0
增量更新完成：新增/更新 5，失败 0，删除 0
```

### 11.2 删除日志

```
文件已删除: D:\download\testVectorData\婚姻.txt
========== 开始删除文件数据 ==========
filePath: D:\download\testVectorData\婚姻.txt
docId: 94a0433e-...
✅ rag_chunks 删除 5 行
✅ Qdrant 删除 5 个 points (status=Completed)
✅ 磁盘文件已删除: .../94a0433e-....txt
✅ rag_documents 删除 1 行
========== 删除完成 ==========
```

### 11.3 四处一致性检查

```sql
-- 应完全对应
SELECT COUNT(*) FROM rag_documents;              -- 指纹数
SELECT COUNT(DISTINCT doc_id) FROM rag_chunks;   -- docId 数
```

```powershell
# Qdrant 数据量
curl.exe http://localhost:6333/collections/agent-rag

# 磁盘文件数
dir "D:\DevelopmentTool\qdrant\rag-files\"
```

**四个数字必须一致** —— 否则有泄漏。

---

## 十二、一句话总结

> **增量更新核心**：
> 1. **指纹表 `rag_documents`** —— 记录文件的 SHA-256 哈希
> 2. **`AtomicBoolean` + `@Scheduled initialDelay`** —— 防并发
> 3. **`@Retryable` + `Semaphore` + `minIntervalMs`** —— 防网络抖动 / 429
> 4. **Qdrant Filter 删除** —— 按 `payload.doc_id` 精确删
> 5. **四处同步** —— Qdrant + rag_chunks + rag_documents + rag-files
>
> **D55 异步化**：
> - **收集 → 提交异步** —— `IncrementalUpdateService` 只负责扫描和收集
> - **有界线程池 + 背压** —— `AsyncIndexService` 负责并发调度
> - **幂等入库** —— `DocumentIngestService.ingestWithFingerprint` 封装"检查 + 入库 + 记账"
> - **embedding 分批** —— 每批 ≤ 10 条（阿里云限制）
> - **效果** —— 50 个文件从 9 分钟 → 2.5 分钟