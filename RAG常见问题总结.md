# RAG 向量化与嵌入模型 · 常见问题总结

> 本文总结 RAG 学习中关于"嵌入模型选择、向量存储、检索结果如何传给大模型"的核心问题

---

## 一、为什么 DeepSeek 需要额外的嵌入模型

### 问题

> 我现在用的模型是 `deepseek-v4-pro`，用什么嵌入模型呢？

### 答案

**DeepSeek 不提供 Embedding 模型**——它的 API 只有对话（Chat）能力，没有向量化端点。

所以 RAG 系统必须**额外选一个嵌入模型**，专门负责"把文本转成向量"。

---

## 二、嵌入模型（Embedding Model） vs 对话模型（Chat Model）

两者是**完全不同的模型**，各司其职。

| 维度 | 对话模型 | 嵌入模型 |
|------|---------|---------|
| **代表** | DeepSeek、GPT-4、Claude | text-embedding-v3、bge-large-zh |
| **输入** | 自然语言文本 | 自然语言文本 |
| **输出** | 自然语言文本（回答） | 向量（float 数组） |
| **作用** | 理解 + 生成 | 语义向量化 |
| **用途** | 对话、推理、工具调用 | 相似度检索 |
| **能否互换** | ❌ | ❌ |

**关键点**：DeepSeek 负责"答"，嵌入模型负责"找"。

---

## 三、嵌入模型选型建议

| 方案 | 推荐度 | 核心优势 | 适合场景 |
|------|:---:|---------|---------|
| **阿里云 `text-embedding-v3`** | ⭐⭐⭐⭐⭐ | 中文强、免费额度、配置简单 | **首选，快速推进项目** |
| **本地 `bge-large-zh-v1.5`** | ⭐⭐⭐⭐ | 中文效果顶尖、数据私有 | 后续对效果和隐私有更高要求 |
| **OpenAI `text-embedding-3-small`** | ⭐⭐⭐ | 多语言均衡、成本低 | 文档包含大量英文 |

### 阿里云 `text-embedding-v3` 关键参数

| 参数 | 值 |
|------|:---:|
| 维度 | 1024（支持自定义） |
| 中文效果 | 优秀 |
| 免费额度 | 100 万 token |
| 兼容性 | OpenAI API 协议 |

---

## 四、不同嵌入模型的向量不能混用

### 核心结论

> **一个知识库，一个模型，从一而终。**

### 为什么不能混用

| 原因 | 说明 |
|------|------|
| **维度可能不同** | OpenAI `text-embedding-3-small` 是 1536 维；`bge-large-zh` 是 1024 维。维度不同，数据库根本没法存 |
| **向量空间不同** | 即使维度相同，不同模型对"相似"的定义也不同。A 模型的向量拿到 B 模型的库里检索，结果全是垃圾 |

### 类比

**不同嵌入模型的向量，就像不同国家的货币**：

- 都叫"钱"
- 但汇率、购买力完全不同
- 不能直接混着用

### 如果一定要换模型

必须**为所有文档重新生成向量**，然后**重建索引**。

生产环境常见的切换流程：

```
1. 新建一个向量字段（或新表）
2. 用新模型对所有文档重新生成向量
3. 验证新向量检索效果
4. 确认无误后，切换查询流量到新字段
5. 旧字段保留一段时间后删除
```

**直接切换模型 = 检索完全失效。**

---

## 五、DeepSeek + 阿里云共存配置

### 问题

> 如果用 `text-embedding-v3`，是不是就不能使用现有的 DeepSeek 配置了？

### 答案

**不需要二选一**——Chat 和 Embedding 是两类模型，用不同服务商，各配各的。

### 完整配置（`application.yml`）

```yaml
spring:
  ai:
    openai:
      # --- 公共配置（可选，会被下面单独配置覆盖）---
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}

      # --- Chat 模型配置（指向 DeepSeek）---
      chat:
        base-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        options:
          model: deepseek-v4-pro

      # --- Embedding 模型配置（指向阿里云百炼）---
      embedding:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: ${DASHSCOPE_API_KEY}
        options:
          model: text-embedding-v3
          dimensions: 1024
```

### 配置要点

| 配置项 | 说明 |
|--------|------|
| `chat.base-url` | DeepSeek 的 API 地址 |
| `chat.api-key` | DeepSeek 的 Key |
| `embedding.base-url` | 阿里云兼容模式地址 |
| `embedding.api-key` | 阿里云百炼的 Key（**与 DeepSeek 不同**） |
| `embedding.options.model` | `text-embedding-v3` |
| `embedding.options.dimensions` | 建议 1024 |

### 代码层注入

Spring AI 会自动创建 `EmbeddingModel` Bean：

```java
@Service
public class MyRagService {

    private final EmbeddingModel embeddingModel;

    public MyRagService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public void someMethod() {
        float[] vector = embeddingModel.embed("这是一段文本");
        // vector.length == 1024
    }
}
```

### 验证配置

```java
@SpringBootTest
class EmbeddingConfigTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void testEmbedding() {
        float[] vector = embeddingModel.embed("测试文本");
        System.out.println("向量维度: " + vector.length);  // 应输出 1024
        assertTrue(vector.length > 0);
    }
}
```

**结果**：DeepSeek 负责对话，阿里云负责向量化，互不干扰。

---

## 六、检索出来的结果，到底发给大模型的是什么？

### 问题

> 使用阿里云的嵌入模型之后，检索出语义相近的向量，这个结果是被转换成中文发送给 DeepSeek 大模型了么？

### 核心答案

> **发给 DeepSeek 的是检索到的【原文】（中文文本），不是向量。**
>
> **向量只是"找路用的索引"，原文才是"要喂给模型的内容"。**

---

### 6.1 向量库里到底存了什么

向量库存的**不只是向量**，而是三个东西**绑在一起**：

```
┌────────────────────────────────────────────┐
│  向量库的一条记录                            │
├────────────────────────────────────────────┤
│  ① 向量（float 数组）  [0.12, -0.34, ...]   │  ← 用来做相似度检索
│  ② 原文（字符串）      "用户购买后7天内可..." │  ← 这才是最终发给模型的
│  ③ 元数据（JSON）      {page:12, source:"手册"}│  ← 用来标注来源
└────────────────────────────────────────────┘
```

**向量和原文是一一对应的**——存的时候绑在一起，取的时候也一起拿出来。

---

### 6.2 检索的完整流程

```
① 用户提问："退货政策是什么？"
        ↓
② 阿里云 text-embedding-v3 把问题转成向量
   [0.21, -0.45, 0.33, ...]   ← 只是"用来搜索的钥匙"
        ↓
③ 拿这个向量去向量库比对
   找到最相似的 Top-K 条记录
        ↓
④ 向量库返回的是【完整记录】，不是只返回向量
   [
     { 向量: [...], 原文: "用户购买后7天内可无理由退货...", 元数据: {page:12} },
     { 向量: [...], 原文: "退货流程：登录→申请→审核...", 元数据: {page:15} },
     { 向量: [...], 原文: "生鲜类不支持无理由退货...", 元数据: {page:18} }
   ]
        ↓
⑤ 只取【原文】部分，拼成 Prompt
   "参考资料：
    [1] 用户购买后7天内可无理由退货...
    [2] 退货流程：登录→申请→审核...
    [3] 生鲜类不支持无理由退货...
    
    问题：退货政策是什么？"
        ↓
⑥ 发给 DeepSeek（纯中文文本，没有向量）
        ↓
⑦ DeepSeek 生成回答："根据平台政策，7天内可无理由退货..."
```

**关键点**：向量在步骤③之后就"用完丢掉了"——它只是用来**定位哪几条记录相关**。真正发给 DeepSeek 的是步骤⑤拼好的**中文文本**。

---

### 6.3 用图书馆类比

| RAG 概念 | 图书馆类比 |
|---------|-----------|
| 向量 | 书的**索书号**（ISBN 编码） |
| 原文 | 书的**正文内容** |
| 向量库 | **检索系统**（按索书号找书） |
| 检索 | 你报一个"主题" → 系统找到索书号相近的书 |
| 拿到什么 | 你拿到的是**书**，不是索书号 |

**你不会把索书号读给朋友听**——你把**书里的内容**讲给朋友。同理，你不会把向量发给 DeepSeek——你发的是**原文**。

---

### 6.4 向量检索到底"检索"了什么

**向量检索比的是"向量之间的距离"，但返回的是"向量对应的原文"。**

```sql
-- pgvector 里的实际查询
SELECT content, metadata
FROM rag_documents
ORDER BY embedding <=> '[0.21, -0.45, ...]'::vector
LIMIT 5;
        ↑                              ↑
   返回原文和元数据               用问题向量做排序依据
```

**SQL 里 `ORDER BY` 用的是向量，`SELECT` 出来的是 `content`（原文）。**

---

### 6.5 为什么不是把向量发给模型

| 原因 | 说明 |
|------|------|
| **模型看不懂向量** | DeepSeek 的输入是文本 token，不是 float 数组。发 `[0.21, -0.45, ...]` 给它，它完全无法理解 |
| **向量里没有信息量** | 向量是文本的**压缩表示**——200 字压缩成 1024 个浮点数，信息已经"模糊化"了，没法还原出具体内容 |

**真正的信息在原文里。**

---

### 6.6 完整数据流

```
【Ingest 阶段】
文档 → 解析 → 切片 → 原文 ┐
                          ├─→ 一起存进向量库
                embed → 向量 ┘

【Retrieve + Generate 阶段】
用户问题 ─→ embed ─→ 问题向量
                       ↓
                向量库比对（只比向量）
                       ↓
              返回原文（不是向量！）
                       ↓
              拼成 Prompt（纯中文文本）
                       ↓
                  DeepSeek
                       ↓
                  中文回答
```

**记住这条线**：

> **向量只用于"找"，原文才用于"答"。**

---

## 七、为什么 RAG 能做到"精准引用原文"

理解上面流程后，这个问题就清楚了：

> **因为发给 DeepSeek 的确实是原文，一字不差。**
>
> **DeepSeek 只是在原文基础上做"语言组织"，不会凭空编造。**

这是 RAG 抑制幻觉的核心机制——**模型没有"自由发挥"的空间**，它只能基于给定的原文回答。

---

## 八、核心要点总结

| # | 要点 |
|:---:|------|
| 1 | DeepSeek 不提供嵌入模型，RAG 需额外选一个 |
| 2 | 对话模型和嵌入模型是两类模型，各司其职 |
| 3 | 推荐阿里云 `text-embedding-v3`（中文强、免费额度、配置简单） |
| 4 | **一个知识库，一个模型，从一而终**——不同模型的向量不能混用 |
| 5 | DeepSeek + 阿里云可以共存配置，各配各的 `base-url` 和 `api-key` |
| 6 | **向量只用于"找"，原文才用于"答"**——发给大模型的是原文 |
| 7 | RAG 抑制幻觉的原理：模型只能基于原文回答，无编造空间 |

---

## 九、下一步

配置好后，D31 的任务是：

```
1. Docker 启动 pgvector
2. 引入 spring-ai-starter-vector-store-pgvector
3. 配置 EmbeddingModel（阿里云 text-embedding-v3）
4. 写一个完整的 ETL：
   解析 → 切片 → 向量化 → 存入 pgvector
5. 测试相似度检索：能根据问题找到相关片段
```

跑通这个流程，你就彻底理解了"向量如何服务于 RAG"。