# Cross-Encoder 详解：概念 · Spring AI 应用 · 本地部署

## 一、Cross-Encoder 是什么

### 1.1 一句话

> **Cross-Encoder（交叉编码器 / Reranker）是一个"打分模型"：把【问题 + 文档片段】拼在一起同时输入，直接输出一个相关度分数（0~1），用于对检索结果做精排。**

它不生成向量，只回答一句话："这段文字和这个问题相不相关、相关多少分？"

### 1.2 与 Bi-Encoder（向量模型）的核心区别

| 对比项 | Bi-Encoder（Embedding 模型） | Cross-Encoder（重排模型） |
|--------|------------------------------|---------------------------|
| 输入 | 文本单独编码成向量 | **问题和文档拼一起输入** |
| 输出 | 一个向量（如 1024 维） | **一个相似度分数（0~1）** |
| 典型代表 | text-embedding-v4、BGE-M3 | bge-reranker-base、bge-reranker-v2-m3 |
| 精度 | 中等（向量近似） | **高（深度交互）** |
| 速度 | 快（可离线算好存库） | 慢（每个片段都要实时算一次） |
| 适用 | 大规模召回（候选池） | 小规模精排（top 几十个） |

```
Bi-Encoder（召回）：
  问题 → 向量A       文档 → 向量B      A 和 B 算余弦相似度
  （各自编码，互不看见，精度有限）

Cross-Encoder（精排）：
  [问题] [SEP] [文档片段] → 直接输出 0.87 分
  （两者深度交互，精度更高，但慢）
```

### 1.3 工作原理

1. 把 **query 和每个候选片段拼接**成一个输入序列（如 `[CLS] 问题 [SEP] 片段 [SEP]`）
2. 经过完整 Transformer 网络（query 和片段**互相注意**，深度交互）
3. 输出层直接给一个相关性分数

> 因为要"问题×每个片段"都算一遍，所以 cross-encoder 只适合小规模重排，不适合大规模检索。

### 1.4 在 RAG 流程中的位置（两阶段检索）

```text
文档库
  ↓ ① 粗召回（Bi-Encoder 向量检索 / 关键词检索）
  Top-100 候选片段          ← 快但糙，夹带噪音
  ↓ ② 精排（Cross-Encoder Rerank）★ 本文主角
  Top-5 高质量片段           ← 慢但准，只对 100 个打分
  ↓ ③ 拼进 Prompt
  大模型回答
```

**这就是为什么叫"重排/精排"：先宽进，再精挑。**

---

## 二、Spring AI 为什么要用 Cross-Encoder

### 2.1 痛点：向量检索"够快但不够准"

- 向量检索是**近似**匹配，Top-1~3 里经常混进"语义沾边但不相关"的片段
- 关键词检索又会召回一堆**字面命中但答非所问**的结果
- 检索质量直接决定回答质量——**召回里没有正确答案，大模型只能瞎编（幻觉）**

### 2.2 解法：两阶段检索（Recruit → Rerank）

```text
第一阶段：大范围召回（recall）
  向量检索 + 关键词检索 → 合并 → Top 100（先宽进，别漏掉对的）
  ↓
第二阶段：Cross-Encoder 精排（rerank）
  对 100 个片段逐一打分 → 重新排序 → 取 Top 5（再精挑，把对的排前面）
```

效果：业界普遍报告 **Rerank 能把 RAG 准确率从 60%~70% 拉到 90%+**（示例：某实验从 65% → 91%）。

### 2.3 Spring AI 里的集成方式（三种）

**方式 A：官方/云服务 Rerank 模型（最省事）**

Spring AI 提供 Rerank 相关抽象，社区/官方有现成实现：
- **阿里百炼 qwen3-rerank**（DashScope，中文效果好，官方推荐）
- **Cohere Rerank**（CohereRerankingModel，英文标杆，100+ 语言）
- Voyage / Jina Rerank 等

```yaml
# 以 DashScope（阿里百炼）为例
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      rerank:
        options:
          model: qwen3-rerank
```

```java
// 注入重排模型
@Autowired
private DashScopeRerankModel rerankModel;

// 配合 Advisor：先召回 Top 200，再重排取 Top 5
RetrievalRerankAdvisor advisor = new RetrievalRerankAdvisor(
    vectorStore,
    rerankModel,
    SearchRequest.builder().topK(200).build()  // 粗召回 200 个候选
);
```

**方式 B：自定义 Advisor（通用，适合任何 rerank 服务）**

```java
@Component
public class RerankAdvisor implements Advisor {
    // 1. 先向量检索 Top N
    // 2. 调 rerank 服务对候选打分
    // 3. 按分数重排，返回 Top K
    // 4. 拼进 Prompt
}
```

**方式 C：本地部署 + 自接（离线、省钱、数据不出内网）**

详见下文第三部分。

### 2.4 什么时候必须上 Rerank（自检清单）

| 场景 | 是否建议 |
|------|---------|
| 文档少（<几十份）、问答简单 | 可以先不上，效果够就省 |
| 文档多、类型杂、相似文档多 | ✅ 强烈建议 |
| 用户会换着说法问（口语/同义） | ✅ 建议 |
| 回答出现"看着相关其实不对" | ✅ 立刻上 |
| 对准确性要求高（医疗/合同/政务） | ✅ 必上 |

---

## 三、本地怎么部署

### 3.1 四种部署方案对比

| 方案 | 技术栈 | 优点 | 缺点 | 适合 |
|------|--------|------|------|------|
| **A. HF TEI 容器** | Docker + HuggingFace TEI | 部署最快、自带 HTTP API | 要 Docker | 推荐首选 |
| **B. ONNX + Java 直嵌** | DJL / ONNX Runtime | 无额外服务、进程内跑 | 要导出 ONNX、调优麻烦 | 追求最简架构 |
| **C. OpenVINO Model Server** | Docker + OpenVINO | CPU 优化好、兼容 Cohere API | 要 Docker、模型要转 IR | Intel 机器 |
| **D. vLLM（score 模式）** | Python + vLLM | 大模型精度高 | 吃显存、重 | 有 GPU 且追求极致 |

### 3.2 方案 A：HuggingFace TEI 容器（推荐，5 分钟跑起来）

**① 拉镜像并启动（CPU 即可）：**

```bash
docker run -d --rm -p 8080:80 \
  -e MODEL_ID=BAAI/bge-reranker-base \
  ghcr.io/huggingface/text-embeddings-inference:latest \
  --model-id BAAI/bge-reranker-base
```

> `BAAI/bge-reranker-base` 是智源开源重排模型（中文友好、轻量、CPU 可跑）。
> 想更强：`BAAI/bge-reranker-v2-m3`（多语言、中英混排效果好，稍大）。

**② 调用（Cohere 兼容 API）：**

```bash
curl -X POST http://localhost:8080/rerank \
  -H "Content-Type: application/json" \
  -d '{
    "query": "签约家庭医生要花多少钱",
    "documents": [
      "家庭医生签约服务按相关规定支付服务费20元/年或70元/年（外地医保）",
      "乙方如出现健康问题应及时联系告知签约家庭医生"
    ],
    "top_n": 2
  }'
```

返回带分数的排序结果，即重排后的 Top N。

### 3.3 方案 B：ONNX 模型 + Java 进程内直跑（无 Docker）

**① 下载 ONNX 模型**（以 bge-reranker 为例）：

```bash
# 从 HuggingFace 下载 ONNX 版本（BAAI/bge-reranker-base-onnx 或自行导出）
# 得到 model.onnx + tokenizer.json + config.json
```

**② Java 依赖：**

```xml
<dependency>
    <groupId>ai.djl</groupId>
    <artifactId>djl-onnxruntime</artifactId>
</dependency>
```

**③ Java 加载打分：**

```java
OnnxScoringModel scoringModel = new OnnxScoringModel("model/bge-reranker-base-onnx/");

// 对每个候选片段打分
float score = scoringModel.score(query, passageText);
```

**④ 接入 RAG：**

```java
// 粗召回 Top 100 → 遍历打分 → 按分数降序取 Top 5
List<Document> candidates = vectorStore.similaritySearch(query, 100);
candidates.sort((a, b) -> Double.compare(
    scoringModel.score(query, b.getContent()),
    scoringModel.score(query, a.getContent())));
List<Document> top5 = candidates.subList(0, 5);
```

### 3.4 模型选择建议

| 模型 | 大小 | 语言 | 场景 |
|------|------|------|------|
| `bge-reranker-base` | ~400MB | 中英 | 通用、CPU 轻量、**首选入门** |
| `bge-reranker-v2-m3` | ~1.2GB | 多语言 | 中英混合、效果更好 |
| `bge-reranker-v2-gemma` | ~4GB | 多语言 | 有 GPU 再上 |
| `qwen3-rerank`（云） | - | 中文 | 不想自己部署、效果顶 |

> 中文场景推荐 **bge-reranker-v2-m3**（精度/体积平衡）；纯测试先用 base。

### 3.5 本地部署后如何接进 Spring AI

- **TEI 容器**：Spring AI 里写一个 `RerankAdvisor`（自定义 Advisor），内部用 RestClient 调 `http://localhost:8080/rerank`，返回分数后重排 → 覆盖 `RerankModel` 接口即可无缝接入
- **ONNX 进程内**：直接像 3.3 ④ 那样在 Advisor 里调用

---

## 四、总结

| 问题 | 答案 |
|------|------|
| Cross-Encoder 是什么 | 把"问题+文档"拼一起输入、直接打分的**精排模型** |
| 为什么 Spring AI 要用 | 向量检索快但糙，重排能把 Top-100 精挑成 Top-5，**准确率 65%→90%+** |
| 怎么集成 | ① 云服务（qwen3-rerank/Cohere）② 自定义 Advisor ③ 本地模型 |
| 本地怎么部署 | 首选 **HF TEI 容器**（5 分钟）；轻量用 **ONNX + DJL 进程内** |
| 中文选什么模型 | 入门 `bge-reranker-base`，正式 `bge-reranker-v2-m3` |

**RAG 黄金流水线：粗召回（向量+关键词）→ Cross-Encoder 精排 → 大模型生成。**
