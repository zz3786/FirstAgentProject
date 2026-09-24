# D29 · RAG 概念学习

> 理解 **索引（Ingest）→ 检索（Retrieve）→ 生成（Generate）** 三阶段流程

---

## 一、RAG 是什么

**RAG = Retrieval-Augmented Generation（检索增强生成）**

一句话：**让大模型"先查资料，再回答"**。

### 为什么需要 RAG

| 问题 | 说明 |
|------|------|
| **模型知识过时** | DeepSeek 训练数据截止某个时间点，之后的事它不知道 |
| **私有数据不懂** | 公司内部文档、产品手册，模型没见过 |
| **幻觉** | 模型会一本正经地胡说八道 |
| **上下文有限** | 塞不进整个知识库，只能塞相关片段 |

**RAG 解决方式**：用户提问时，**先从知识库检索相关片段，拼进 Prompt，再让模型基于这些片段回答**。

---

## 二、核心三阶段

```text
┌─────────────────────────────────────────────────────────┐
│  阶段 1：Ingest（索引）—— 离线，一次或定期               │
│                                                         │
│  文档 → 解析 → 切片 → 向量化 → 存入向量库                │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  阶段 2：Retrieve（检索）—— 每次提问时                  │
│                                                         │
│  用户问题 → 向量化 → 向量库相似度检索 → Top-K 片段      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  阶段 3：Generate（生成）—— 每次提问时                  │
│                                                         │
│  [片段 + 用户问题] → 拼成 Prompt → 模型生成答案          │
└─────────────────────────────────────────────────────────┘
```

---

## 三、阶段 1：Ingest（索引）

### 做什么

把**非结构化文档**变成**可检索的向量**。

### 具体步骤

```text
① 加载（Load）
   读取 PDF / Word / Markdown / HTML / 数据库
        ↓
② 解析（Parse）
   提取纯文本，去掉格式、页眉页脚
        ↓
③ 切片（Chunk）
   长文本切成小段（每段 200~1000 字）
   保留重叠（overlap 50~100 字），避免语义被切断
        ↓
④ 向量化（Embed）
   每段文本 → 一个向量（比如 1536 维的 float 数组）
   相似语义的文本，向量距离近
        ↓
⑤ 存储（Store）
   向量 + 原文 + 元数据 → 存入向量数据库
```

### 关键概念：Embedding

**Embedding = 把文本转成向量**，让"语义相似"变成"距离相近"。

```text
"我喜欢吃苹果"  →  [0.12, -0.34, 0.56, ...]   1536 维
"我爱吃水果"    →  [0.11, -0.32, 0.55, ...]   ← 距离很近
"今天下雨了"    →  [-0.78, 0.21, -0.09, ...]  ← 距离很远
```

**Embedding 模型**（与 Chat 模型是**两个不同的模型**）：

| 模型 | 维度 | 厂商 |
|------|:---:|------|
| `text-embedding-3-small` | 1536 | OpenAI |
| `text-embedding-3-large` | 3072 | OpenAI |
| `bge-large-zh` | 1024 | 智源 |
| `text-embedding-v3` | 1024 | 阿里 |

### 关键概念：Chunk（切片）

**为什么要切片**：

- 模型上下文有限（比如 8K token）
- 检索粒度要细（整本书检索不到具体答案）
- 切片太大 → 检索到的内容杂；切片太小 → 语义不完整

**切片策略**：

| 策略 | 说明 |
|------|------|
| **固定长度** | 每 500 字切一段，简单但可能切断句子 |
| **按段落** | 按 `\n\n` 切，语义完整 |
| **递归切分** | 先按段落，太长再按句子，还长再按字符 |
| **语义切分** | 用模型判断语义边界，效果最好但贵 |

### 关键概念：向量库

**专门存向量、做相似度检索的数据库**。

| 向量库 | 特点 | 适用 |
|--------|------|------|
| **pgvector** | PostgreSQL 插件，与关系数据共存 | **推荐入门** |
| **Milvus** | 专业向量库，性能强 | 大规模 |
| **Qdrant** | Rust 编写，轻量 | 中小规模 |
| **Redis Stack** | Redis 加向量检索 | 已有 Redis |
| **Chroma** | Python 生态，简单 | 原型 |

**你项目已用 Redis**——后续可以用 **Redis Stack** 或 **pgvector**。

### Spring AI 对应 API

```java
// 1. 读取文档
Resource resource = new FileSystemResource("manual.pdf");
DocumentReader reader = new PagePdfDocumentReader(resource);
List<Document> docs = reader.get();

// 2. 切片
TextSplitter splitter = new TokenTextSplitter(500, 100, 10, 5000, true);
List<Document> chunks = splitter.apply(docs);

// 3. 向量化 + 存储
vectorStore.add(chunks);
```

---

## 四、阶段 2：Retrieve（检索）

### 做什么

用户提问时，**从向量库找出最相关的 Top-K 片段**。

### 具体步骤

```text
用户问题："退货政策是什么？"
        ↓
① 问题向量化
   "退货政策是什么？" → [0.21, -0.45, ...]
        ↓
② 相似度检索
   在向量库中找距离最近的 K 个片段
   （余弦相似度 / 欧氏距离 / 内积）
        ↓
③ 返回 Top-K
   [{片段1, 相似度0.92}, {片段2, 相似度0.88}, ...]
```

### 相似度算法

| 算法 | 公式 | 适用 |
|------|------|------|
| **余弦相似度** | `cos(θ)` | 最常用，不受长度影响 |
| **欧氏距离** | `√Σ(a-b)²` | 关心绝对距离 |
| **内积** | `Σ(a×b)` | 归一化后等价于余弦 |

**pgvector 里的操作符**：

| 操作符 | 含义 |
|:---:|------|
| `<->` | 欧氏距离 |
| `<#>` | 负内积 |
| `<=>` | 余弦距离 |

### 检索策略

| 策略 | 说明 |
|------|------|
| **Top-K** | 取最相似的 K 个 |
| **相似度阈值** | 低于 0.7 的丢弃 |
| **MMR** | 最大边际相关，兼顾多样性 |
| **混合检索** | 向量 + 关键词（BM25），提高召回 |

### 关键概念：Rerank（重排序）

**问题**：向量检索快但粗，可能召回一堆"看起来相似"但实际不相关的。

**方案**：用 **Cross-Encoder** 模型对 Top-K 结果精排：

```text
向量检索 Top-20 → Rerank → 精排后 Top-5 → 给模型
```

**代价**：多一次模型调用，但准确率显著提升。

### Spring AI 对应 API

```java
SearchRequest request = SearchRequest.builder()
        .query("退货政策是什么？")
        .topK(5)
        .similarityThreshold(0.7)
        .build();

List<Document> results = vectorStore.similaritySearch(request);
```

---

## 五、阶段 3：Generate（生成）

### 做什么

把**检索到的片段**和**用户问题**拼成 Prompt，发给模型生成答案。

### 具体步骤

```text
① 组装 Prompt

   System: 你是知识库助手，只能基于以下资料回答，不要编造。
   
   【参考资料】
   [1] 用户购买后 7 天内可无理由退货，需保持商品完好...
   [2] 退货流程：登录 → 我的订单 → 申请退货 → 等待审核...
   [3] 生鲜类商品不支持无理由退货...
   
   User: 退货政策是什么？
   
        ↓
② 模型生成
   基于参考资料，生成自然语言回答
        ↓
③ 返回给用户
   "根据平台政策，用户购买后 7 天内可无理由退货..."
```

### 关键设计：Prompt 模板

```text
你是一个知识库助手，请严格根据以下参考资料回答用户问题。

要求：
1. 只使用参考资料中的信息，不要编造
2. 如果参考资料中没有答案，明确说"资料中未提及"
3. 回答时标注引用来源（如 [1]）
4. 保持简洁，不超过 200 字

【参考资料】
{context}

【用户问题】
{question}
```

**核心**：**强制模型"只基于资料回答"**——这是抑制幻觉的关键。

### Spring AI 对应 API

**方式 1：手动拼**

```java
List<Document> docs = vectorStore.similaritySearch(query);
String context = docs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n"));

String prompt = "参考资料：\n" + context + "\n\n问题：" + query;
String answer = chatClient.prompt().user(prompt).call().content();
```

**方式 2：用 `QuestionAnswerAdvisor`（推荐）**

```java
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().topK(5).build())
                        .build()
        )
        .build();

String answer = client.prompt().user("退货政策是什么？").call().content();
```

**`QuestionAnswerAdvisor` 自动做了三件事**：

1. 把用户问题拿去向量库检索
2. 把检索结果拼进 SystemMessage
3. 发给模型

**它和你已有的 `MemoryRetrievalAdvisor` 思路一样**——只是检索源从"记忆"换成"知识库"。

---

## 六、和你现有项目的对比

**你现在的 `MemoryRetrievalAdvisor` 就是一次"手写的 RAG"**：

| 维度 | 你的 MemoryRetrievalAdvisor | 标准 RAG |
|------|:---:|:---:|
| **检索源** | `LTM:*`（用户记忆） | 知识库文档 |
| **检索方式** | 关键词字符串匹配 | 向量相似度 |
| **存储** | Redis Hash | 向量库（pgvector） |
| **Embedding** | 无 | 有 |
| **注入** | SystemMessage | SystemMessage |
| **Advisor** | 自定义 | `QuestionAnswerAdvisor` |

**学习 RAG 时**，你会发现自己已经实现了 80% 的逻辑——**只差"向量化"和"向量库"两块**。

---

## 七、完整流程示例

### 用户提问："退货政策是什么？"

```text
【离线阶段】（已经做过）
1. 加载《用户手册.pdf》 → 100 页
2. 解析成纯文本 → 20 万字
3. 切片 → 500 个片段（每段 500 字）
4. 向量化 → 500 个 1536 维向量
5. 存入 pgvector

【在线阶段】（每次提问）
6. 用户问题 → "退货政策是什么？"
7. 向量化 → [0.21, -0.45, ...]
8. 向量库检索 Top-5 → 
   [片段12: "7天无理由退货...", 相似度0.92]
   [片段45: "退货流程：登录→...", 相似度0.88]
   [片段78: "生鲜类不支持...", 相似度0.85]
   ...
9. 组装 Prompt：
   System: "基于以下资料回答..."
   资料: [片段12, 片段45, 片段78]
   User: "退货政策是什么？"
10. 模型生成：
    "根据平台政策，购买后 7 天内可无理由退货... [1]"
11. 返回给用户
```

---

## 八、动手路线图（第 5~8 周）

| 周 | 任务 | 关键点 |
|:---:|------|--------|
| **第 5 周** | 搭建 pgvector + 文档解析 + 切片 | Embedding 模型选择 |
| **第 6 周** | Top-K 检索 + `QuestionAnswerAdvisor` | 相似度阈值调优 |
| **第 7 周** | 混合检索 + Rerank + 引用来源 | 召回率提升 |
| **第 8 周** | 和现有记忆系统整合 | 多路 Advisor 共存 |

---

## 九、几个关键坑

### 坑 1：Embedding 模型和 Chat 模型是两回事

- **Chat 模型**（DeepSeek）：生成对话
- **Embedding 模型**：把文本转向量

**DeepSeek 没有公开 Embedding API**——你需要：

- 用 OpenAI 的 `text-embedding-3-small`
- 或用阿里的 `text-embedding-v3`
- 或本地部署 BGE 模型

### 坑 2：向量维度必须一致

Embedding 模型输出的维度（比如 1536）必须和向量库表的维度**完全一致**——否则插入报错。

### 坑 3：切片不是越小越好

- 太小 → 语义不完整，检索到碎片
- 太大 → 检索不精确，噪声多
- **经验值**：200~1000 字，重叠 10~20%

### 坑 4：Prompt 必须强约束

不写"只基于资料回答"，模型会：

- 用训练数据里的知识"补充"
- 编造不存在的细节
- 回答得看似合理但错误

### 坑 5：RAG 不是万能的

- 问题太复杂 → 检索不到相关片段
- 知识库质量差 → 检索到错误内容
- 需要跨多个文档推理 → RAG 处理不了（要用 Agent）

---

## 十、一句话总结

> **RAG = 索引（存）→ 检索（查）→ 生成（答）**
>
> - **索引**：文档切片 → 向量化 → 存向量库（离线，一次）
> - **检索**：问题向量化 → 相似度搜索 → Top-K 片段（每次）
> - **生成**：片段 + 问题 → Prompt → 模型回答（每次）
>
> **核心价值**：让模型基于**外部知识**回答，而不是只靠训练数据。

---

## 十一、下一步

D29 是**概念学习**，不用写代码。D30 开始动手：

```text
1. Docker 启动 pgvector
2. 引入 spring-ai-starter-vector-store-pgvector
3. 配置 Embedding 模型
4. 写一个最简单的"上传文档 → 检索 → 回答"
```

---

## 十二、推荐资料

| 类型 | 资源 |
|------|------|
| **论文** | *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*（RAG 原始论文） |
| **教程** | Spring AI 官方文档 "Retrieval Augmented Generation" 章节 |
| **视频** | DeepLearning.AI 的 "Building and Evaluating Advanced RAG" |
| **实战** | LangChain 的 RAG 教程（看思路，语言无关） |

---

**D29 完成标志**：能用自己的话向别人解释"索引 → 检索 → 生成"三阶段，以及每阶段的关键技术点。

理解了这个框架，D30 开始写代码时你会很清楚每一步在做什么。