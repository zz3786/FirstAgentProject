# Java 后端转 Agent 开发 · 实战项目

> Spring AI 驱动的企业级 Agent 工程底座 —— 从 RAG 到多租户、从六层记忆到主动推荐

---

## 一、项目简介

基于 **Spring Boot 3 + Spring AI 1.1.8** 的完整 Agent 工程化项目。目标不是"跑通 demo"，而是构建一套**可运维、可观测、可降级、可多租户**的 Agent 底座。

**已实现能力**：

- ✅ RAG 全链路（解析 → 切片 → 向量化 → 混合检索 → 重排 → 注入）
- ✅ 六层记忆体系（会话 / 长期 / 偏好 / 兴趣 / 对话历史向量 / 检索画像）
- ✅ 工具调用 + 安全包装（超时 / 异常兜底 / 审计）
- ✅ 澄清判定（检索模糊时反问）
- ✅ 主动推荐（回答后追加相关文档）
- ✅ 多租户隔离（Redis / Qdrant / MySQL 全链路）
- ✅ 异步索引（大批量文档导入优化）
- ✅ 语义缓存（跨对话复用答案）
- ✅ 审计日志（独立 logger 分流）

---

## 二、技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.5.15 |
| AI 框架 | Spring AI | 1.1.8 |
| 向量库 | Qdrant | 1.x |
| 缓存 | Redis (Redis Stack) | 7.x |
| 关系库 | MySQL | 8.x |
| Embedding | 阿里云 text-embedding-v4 | 1024 维 |
| 重排模型 | BAAI/bge-reranker-base (TEI) | CPU 版 |
| 对话模型 | DeepSeek | deepseek-v4-pro |
| OCR | Tesseract (Tess4J) | 5.x |
| 构建 | Maven | 3.9+ |

---

## 三、模块架构

### 3.1 模块依赖

```
agent-api           ← 对外接口层（启动模块）
    └── agent-core  ← 核心业务编排
            ├── agent-tools      ← 工具定义
            ├── agent-memory     ← 六层记忆体系
            ├── agent-rag        ← RAG（检索 + 摄取）
            │       └── agent-cache  ← 语义缓存
            └── agent-common     ← 共享工具类
```

**依赖原则**：单向、无循环。

| 模块 | 职责 | 依赖内部模块 |
|------|------|:---:|
| `agent-common` | 共享工具（PromptUtils / 租户 / 审计） | 无 |
| `agent-tools` | 可被 LLM 调用的工具 | 无 |
| `agent-cache` | 语义缓存 + Embedding 缓存 | 无 |
| `agent-memory` | 六层记忆体系（会话 / 长期 / 偏好 / 兴趣 / 对话历史向量 / 检索画像） | agent-tools |
| `agent-rag` | RAG 检索 + 文档摄取 | agent-cache, agent-common |
| `agent-core` | 业务编排 + Advisor 装配 | 上述全部 |
| `agent-api` | Controller + 启动类 | agent-core, agent-rag |

### 3.2 `agent-rag` 内部分包

单模块，逻辑隔离为 3 个子包：

```
org.example.rag
  ├── ingest/      ← 文件通道（扫描 / 解析 / 切片 / 入库 / 异步）
  ├── retrieval/   ← 检索（混合检索 / 重排 / 澄清 / 推荐 / 画像）
  └── shared/      ← 共享模型（RagChunk / RagFilter / Qdrant 索引配置）
```

**为什么不分 Maven 模块**：当前只有 1 个输入源（目录扫描），抽模块是负收益。逻辑分包已足够隔离职责。

### 3.3 `agent-memory` 内部分包

```
org.example.memory
  ├── advisor/       ← 6 个记忆相关 Advisor
  ├── repository/    ← RedisChatMemoryRepository（会话记忆读写）
  ├── memory/        ← LongTermMemoryService + ConversationMemoryService
  ├── preference/    ← UserPreferenceService
  ├── interest/      ← UserInterestService
  ├── tools/         ← MemoryTools / PreferenceTools / InterestTools
  ├── config/        ← ChatMemoryConfig / CompactionConfig / ConversationMemoryProperties
  └── utils/         ← SensitiveDataMasker
```

---

## 四、核心机制

### 4.1 六层记忆体系

记忆分两类：**内容层**（记住什么）和**模式层**（怎么问 / 关心什么 / 要什么风格）。

**内容层（3 层）**：

| 层 | 中文名 | 存储 | Key 示例 | 写入方 | 读取方式 |
|----|--------|------|---------|--------|---------|
| 1 | 会话记忆 | Redis List | `CHAT:hospital-a:user-alice:sess-1` | Spring AI 自动 | 全量注入 |
| 2 | 长期记忆 | Redis Hash | `LTM:hospital-a:user-alice` | 模型调工具 | 中文 2-gram 关键词 |
| 3 | 用户偏好 | Redis Hash | `USER_PREF:hospital-a:user-alice` | 模型调工具 | 全量注入 |

**模式层（3 层）**：

| 层 | 中文名 | 存储 | Key 示例 | 写入方 | 读取方式 |
|----|--------|------|---------|--------|---------|
| 4 | 兴趣标签 | Redis Hash | `USER_INTEREST:hospital-a:user-alice` | 模型调工具 | 按权重 TopN |
| 5 | 对话历史向量 | Redis Vector | `conv-mem:<uuid>`（`user_id` 字段隔离） | 每轮自动 | 向量语义检索 |
| 6 | 检索画像 | Redis Hash | `RETRIEVAL_PROFILE:hospital-a:user-alice` | 显式工具 | 调整检索权重 |

**每层解决的问题**：

| 层 | 解决的问题 |
|----|-----------|
| 会话记忆 | "刚才聊了啥" |
| 长期记忆 | "用户告诉过我的重要事实" |
| 用户偏好 | "用户的稳定偏好（城市 / 语言）" |
| 兴趣标签 | "用户关注什么技术领域" |
| 对话历史向量 | "上次类似问题怎么答的"（语义模糊也能召回） |
| 检索画像 | "用户喜欢精确条款还是语义摘要" |

**记忆 vs 缓存**：

- `agent-memory` = 业务记忆 —— 不能随意清空（用户会感知）
- `agent-cache` = 性能优化 —— 可随时清空（用户无感知）

### 4.2 RAG 全链路

```
文档入库链路：
  目录扫描 → 解析（Tika/PDFBox/POI/OCR）
       → 切片（TokenTextSplitter）
       → 元数据注入（doc_id / source / department / year /
                     security_level / status / tenant_id /
                     page_number / total_chunks）
       → 向量化（Qdrant，每批 ≤ 10 条）
       → 关键词索引（MySQL rag_chunks）
       → 指纹记录（MySQL rag_documents）

检索链路：
  用户 query
       → 语义缓存查询（命中则直接返回）
       → 混合检索（向量 + 关键词）
       → 加权融合 / RRF 融合
       → TEI 重排
       → 澄清判定（分数低则反问）
       → 注入 Prompt
       → 回答后追加推荐块
```

### 4.3 Advisor 链

按 `order` 从小到大的执行顺序：

| Advisor | order | 职责 | 类型 |
|---------|:---:|------|:---:|
| `MessageChatMemoryAdvisor` | 极小 | 读写 CHAT，注入会话历史 | 读 |
| `CompactingChatMemoryAdvisor` | 50 | CHAT 超 100 条压缩为摘要 | 读 |
| `PreferenceAdvisor` | 100 | 注入用户偏好 | 读 |
| `UserInterestAdvisor` | 120 | 注入兴趣标签 | 读 |
| `RagAdvisor` | 150 | 注入知识库资料 | 读 |
| `LongTermMemoryAdvisor` | 200 | 检索 LTM 注入 | 读 |
| `ConversationRetrievalAdvisor` | 210 | 检索对话历史向量注入 | 读 |
| `ToolLoggingAdvisor` | LOWEST | 打印完整 prompt | 观测 |
| `ConversationMemoryAdvisor` | 250 | 本轮对话写入向量库 | 写 |

**读 → 模型 → 写** 的洋葱模型：order 小的在外层，`doOnComplete` 按 order 从大到小触发。

### 4.4 工具安全包装

每个原始 `ToolCallback` 被 `SafeToolCallback` 包裹：

- 超时控制（10 秒）
- 异常转友好文本（不暴露 Java 异常名）
- 入参出参日志（出参超 5000 字截断）
- `ToolContext` 透传（userId 从 context 取，不让 LLM 填）

### 4.5 多租户隔离

**tenantId 贯穿全链路**：

| 存储 | 隔离方式 |
|------|---------|
| Redis 所有 key | `{type}:{tenantId}:{userId}:...` |
| Qdrant payload | `tenant_id` 字段（TAG 索引） |
| MySQL `rag_chunks` | `tenant_id` 列（含索引） |
| 语义缓存 | `user_id` 字段 = `tenantId:userId` |
| 对话历史向量 | `user_id` 字段同上 |

**租户级权限**：`RagFilter.toExpression()` 里 `tenant_id == 'xxx'` —— 硬过滤，优先级最高。

### 4.6 异步索引

```
IncrementalUpdateService（扫描器）
   ↓ 收集待入库文件
AsyncIndexService（调度器）
   ↓ 有界线程池（4 并发）+ 信号量限流
DocumentIngestService（入库原子操作）
   ↓ ingestWithFingerprint（幂等 + hash + 入库 + 写指纹）
```

**关键配置**：

- 并发数：4（可配，不能太大防 API 限流）
- 限流：`Semaphore` + `minIntervalMs`（默认 200ms）
- 批大小：embedding API 单批 ≤ 10（阿里云限制）
- 超时：单文件 300 秒

**效果**：50 个文件从 9 分钟 → 约 2.5 分钟（3-4 倍提速）。

### 4.7 审计日志

独立 logger `AUDIT`，通过 `logback-spring.xml` 分流到 `logs/audit.log`：

```xml
<logger name="AUDIT" level="INFO" additivity="false">
    <appender-ref ref="AUDIT_FILE"/>
    <appender-ref ref="CONSOLE"/>
</logger>
```

**审计事件类型**：

- `[LOGIN_OK]` / `[LOGIN_FAIL]` / `[LOGOUT]`
- `[CROSS_TENANT]` 跨租户访问尝试
- `[DEPT_DENIED]` 越权部门过滤
- `[SENSITIVE]` 清空会话 / 缓存
- `[DOC_INGEST]` / `[DOC_DELETE]` 文档变更

---

## 五、接口

### 5.1 对话接口

| 方法 | 路径 | 说明 | 返回 |
|------|------|------|------|
| GET | `/chat/sync?message=xxx` | 同步对话 | `ApiResponse<String>` |
| GET | `/chat/stream?message=xxx` | 流式对话 | `Flux<String>`（SSE） |
| GET | `/chat/streamR?message=xxx&sessionId=xxx&departments=xxx` | 主入口：记忆 + 工具 + RAG + 推荐 | `Flux<String>`（SSE） |
| POST | `/chat/clear?sessionId=xxx` | 清空会话 | `ApiResponse<Void>` |
| GET | `/chat/sessions` | 列出当前用户会话 | `ApiResponse<List<String>>` |

### 5.2 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/login?code=DEMO-001` | 邀请码登录 |
| GET | `/auth/me` | 当前用户信息 |
| POST | `/auth/logout` | 退出登录 |

**演示邀请码**：

| 邀请码 | 用户 | 租户 | 部门 | 密级 |
|--------|------|------|------|:---:|
| `DEMO-001` | user-alice | hospital-a | 财务部 | 3 |
| `DEMO-002` | user-bob | hospital-a | 研发部 | 2 |
| `DEMO-003` | user-charlie | hospital-b | 财务部 | 3 |

### 5.3 流式响应格式

**正常回答 + RAG 引用 + 推荐**：

```
家庭医生签约后可享受以下服务：
- **基本公共卫生服务**：免费享受 12 大类 46 项服务 [资料 1]
...
---
**📖 引用来源：**
- [资料 1]：《家庭医生有偿签约服务协议书》第 1 页 [查看原文](/fap/rag/file/download/xxx)

---
[RECOMMEND]
📚 你可能还想了解：
1. 《XXX》 — 摘要... [查看原文](/fap/rag/file/download/yyy)
```

**澄清反问**（检索模糊时）：

```
[CLARIFY]抱歉，「XXX」这个问题我不太确定您具体想问什么。
我找到了这些可能相关的资料：
1. 《...》
能否补充一下您的具体需求？
```

---

## 六、启动方式

### 6.1 前置依赖

```bash
# Redis（含 RediSearch 模块）
docker run -d --name redis-stack -p 6379:6379 -p 8001:8001 \
  redis/redis-stack:latest

# Qdrant
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 \
  -v $(pwd)/qdrant_data:/qdrant/storage qdrant/qdrant

# TEI 重排服务（模型目录需提前下载 bge-reranker-base）
docker run -d --name tei-reranker -p 8081:80 --memory=4g \
  -v /path/to/bge-reranker-base:/data \
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.9 \
  --model-id /data

# MySQL
docker run -d --name mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=xxx mysql:8
```

### 6.2 环境变量

```bash
export DEEPSEEK_API_KEY=sk-xxx       # DeepSeek 对话模型
export DASHSCOPE_API_KEY=sk-xxx      # 阿里云 Embedding
export MYSQL_HOST=localhost
export MYSQL_USER=root
export MYSQL_PASSWORD=xxx
```

### 6.3 编译 & 启动

```bash
mvn clean install -DskipTests
mvn spring-boot:run -pl agent-api
```

或 IDEA 里直接运行 `agent-api` 模块的 `FirstAgentProjectApplication`。

### 6.4 访问

- 聊天界面：http://localhost:8080/fap/chat.html
- Qdrant Dashboard：http://localhost:6333/dashboard
- Redis UI：http://localhost:8001

---

## 七、数据准备

### 7.1 目录结构（租户隔离）

```
D:/download/testVectorData/
  ├── hospital-a/           ← tenantId
  │   ├── 财务部/            ← department
  │   │   └── 2024报销制度.pdf
  │   └── 人事部/
  └── hospital-b/
      └── 财务部/
```

**扫描时自动提取**：

- `{listenFilesDir}/{tenantId}/{department}/{file}`
- 无租户目录 → `tenantId = "default"`
- 无部门目录 → `department = "公开"`

### 7.2 MySQL 表结构

```sql
-- 文档 chunk（关键词检索用）
CREATE TABLE rag_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id VARCHAR(64) DEFAULT 'default',
    doc_id VARCHAR(64),
    chunk_index INT,
    source VARCHAR(255),
    content TEXT,
    file_path VARCHAR(255),
    department VARCHAR(50),
    year INT,
    content_type VARCHAR(20),
    security_level INT,
    status VARCHAR(20),
    page_number INT,
    total_chunks INT,
    created_at TIMESTAMP,
    FULLTEXT(content) WITH PARSER ngram,
    INDEX idx_tenant_id (tenant_id)
);

-- 文件指纹（幂等控制）
CREATE TABLE rag_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_path VARCHAR(500) UNIQUE,
    file_hash VARCHAR(64),
    doc_id VARCHAR(64),
    source VARCHAR(255),
    file_size BIGINT,
    last_modified DATETIME,
    indexed_at DATETIME
);
```

---

## 八、常见问题

**Q1：`batch size is invalid, it should not be larger than 10`？**

阿里云 embedding 单批上限 10 条。`DocumentIngestService.addToVectorStoreInBatches` 已分批处理。

**Q2：向量路返回 0 条？**

检查 Qdrant 里 `department` / `year` 是否有值 —— 老数据可能没有。重新入库即可。

**Q3：`Syntax error at offset XX near user-alice`？**

RediSearch TAG 字段不允许 `-` / `:` 等保留字符。所有 user_id 存储前用 `escapeRedisTag` 转义（`-` → `_`）。

**Q4：TEI 超时 60 秒？**

CPU 版 TEI 慢是常态。`RemoteRerankService` 已加 8 秒超时降级 —— 超时用原始顺序，不阻断主流程。

**Q5：`/auth/me` 500？**

接口不存在时 Spring 会当作静态资源请求抛 `NoResourceFoundException`。已加专门 handler 返回 404。

**Q6：多租户数据泄露？**

检查 4 个点：① Redis key 带租户前缀 ② Qdrant payload 有 `tenant_id` ③ MySQL `tenant_id` 列有值 ④ 检索 filter 里 `tenantId` 不为 null。

---

## 九、后续路线

| 阶段 | 任务 | 状态 |
|------|------|:---:|
| 第一阶段 | 工具调用 + 流式 + 三层记忆 | ✅ |
| 第二阶段 | RAG + 元数据过滤 + 六层记忆 + 多租户 + 主动推荐 + 异步索引 | ✅ |
| **第三阶段** | **工作流编排 + 多智能体协作** | ⏳ 进行中 |
| 生产化 | 可观测性 + 部署 + 压测调优 | ⏳ |

### 第三阶段规划

- **工作流引擎**：DAG 编排 / 条件分支 / 并行执行
- **多 Agent 协作**：主从 Agent / 角色分工 / 消息传递
- **状态管理**：长时任务的持久化 / 断点续跑
- **MCP 集成**：外部工具生态接入
- **人机协同**：HITL（Human-in-the-loop）审批节点

---

## 十、许可

个人学习项目，可自由参考。