# Spring AI 六层记忆体系详解

## 一、六层记忆总览

| 维度 | CHAT（会话记忆） | LTM（长期记忆） | USER_PREF（用户偏好） | USER_INTEREST（兴趣标签） | CONV_MEM（对话向量） | RETRIEVAL_PROFILE（检索画像） |
|------|-----------------|----------------|----------------------|--------------------------|---------------------|------------------------------|
| 存什么 | 本次会话的完整对话 | 跨会话的重要事实 | 结构化标签 | 关注的技术领域 + 权重 | 跨会话的原始对话轮次 | 检索偏好参数 |
| 数据结构 | Redis List | Redis Hash | Redis Hash | Redis Hash | Redis 向量索引 | Redis Hash |
| 谁写 | RedisChatMemoryRepository | LongTermMemoryService | UserPreferenceService | UserInterestService | ConversationMemoryService | RetrievalProfileService |
| 触发 | 自动（每轮对话） | 模型主动调工具 | 模型主动调工具 | 模型主动调工具 | 自动（每轮对话） | 显式工具 |
| 读取 | MessageChatMemoryAdvisor 自动 | LongTermMemoryAdvisor 关键词检索 | PreferenceAdvisor 全量注入 | UserInterestAdvisor 按权重 TopN | ConversationRetrievalAdvisor 向量检索 | HybridSearchService 调整权重 |
| 检索方式 | 滑动窗口（最近 N 条） | 中文 2-gram 关键词 | 按 key 直读 | 权重降序 TopN | 向量语义相似度 | 按偏移调整融合权重 |
| 范围 | 当前会话 | 当前用户 | 当前用户 | 当前用户 | 当前用户 + 排除当前会话 | 当前用户 |
| 生命周期 | TTL 7 天，窗口 200 条 | TTL 180 天 | TTL 360 天 | TTL 180 天 | TTL 180 天 | TTL 90 天 |
| 典型内容 | "我叫小明"、"帮我算123+456" | "订单1001退货，客服承诺3天" | city=合肥、language=中文 | "Java转Agent"=0.3、"RAG"=0.5 | "上次聊的采购项目进展" | 偏好部门 / 权重偏移 |

> **分类**：前 4 层是**内容层**（记住什么），后 2 层是**模式层**（怎么问 / 关心什么）。

---

## 二、Redis 里的实际结构对比

```
CHAT:hospital-a:user-alice:s1 (list)
  ├─ [0] {"type":"USER","textContent":"我叫小明",...}
  ├─ [1] {"type":"ASSISTANT","textContent":"你好小明",...}
  └─ [2] {"type":"USER","textContent":"我叫什么名字",...}

LTM:hospital-a:user-alice (hash)
  └─ 1790060354427_0.061: {"content":"用户上周订单1001退货，客服承诺3天内处理","type":"FACT","time":"2026-09-22"}

USER_PREF:hospital-a:user-alice (hash)
  ├─ city: 合肥
  └─ language: 中文

USER_INTEREST:hospital-a:user-alice (hash)
  ├─ "Java转Agent": {"tag":"Java转Agent","weight":0.30,"lastSeenAt":...,"hitCount":1}
  └─ "RAG":         {"tag":"RAG","weight":0.50,"lastSeenAt":...,"hitCount":3}

RETRIEVAL_PROFILE:hospital-a:user-alice (hash)
  ├─ preferred_departments: "财务部,人事部"
  ├─ preferred_doc_types:   "制度,通知"
  ├─ weight_bias_vec:       "+0.05"
  ├─ weight_bias_kw:        "-0.05"
  ├─ top_k_bias:            "+1"
  └─ source:                "explicit"

conv-mem:<UUID> (向量索引条目)
  ├─ text: "我那个退货的事怎么样了"
  └─ metadata:
       user_id           = "hospital-a_user_alice"     ← 下划线转义（RediSearch 规则）
       conversation_id   = "hospital-a:user-alice:s2"
       turn_index        = 5
       user_message      = "我那个退货的事怎么样了"
       assistant_message = "您订单1001的退货..."
       timestamp         = 1790060400000

conv-turn:hospital-a:user-alice:s2 (string)
  └─ "5"    ← 该会话已进行的轮次数
```

### 六种数据结构的差异

| 存储 | 为什么用这种结构 |
|------|----------------|
| CHAT: List | 对话天然有序，需要按时间读取、滑窗裁剪 |
| LTM: Hash | 每条记忆独立，需要按 ID 增删、按内容检索 |
| USER_PREF: Hash | 键值对结构，city=合肥 一目了然，方便直接读某个字段 |
| USER_INTEREST: Hash | 标签 + 权重 + 计数，需要按权重排序取 TopN |
| RETRIEVAL_PROFILE: Hash | 结构化字段，作为检索时的"调参输入" |
| CONV_MEM: 向量索引 | 需要"按语义相似度召回"，不是精确匹配 |

---

## 三、写入链路对比

### CHAT: 自动写

```
用户请求 → ChatClient.stream()
    ↓
MessageChatMemoryAdvisor.after()   ← 框架自动
    ↓
chatMemory.saveAll(conversationId, messages)
    ↓
RedisChatMemoryRepository.saveAll()
    ↓
redis.opsForList().rightPush("CHAT:" + conversationId, json)
```

### LTM: 模型主动写

```
用户："记住，我订单1001退货了"
    ↓
模型判断"这是重要事实"
    ↓
返回 tool_call: saveLongTermMemory(type=FACT, content="用户订单1001退货")
    ↓
LongMemoryTools.saveLongTermMemory()   ← userId 从 ToolContext 取
    ↓
LongTermMemoryService.save()
    ↓
redis.opsForHash().put("LTM:" + fullUserId, id, json)
```

### USER_PREF: 模型主动写

```
用户："我常住在合肥"
    ↓
模型判断"这是一个可结构化的偏好"
    ↓
返回 tool_call: savePreference(key="city", value="合肥")
    ↓
PreferenceTools.savePreference()   ← userId 从 ToolContext 取
    ↓
UserPreferenceService.save()
    ↓
redis.opsForHash().put("USER_PREF:" + fullUserId, "city", "合肥")
```

### USER_INTEREST: 模型主动写（D53 新增）

```
用户："我在学 Spring AI 和 RAG"
    ↓
模型判断"用户明确表达了兴趣领域"
    ↓
返回 tool_call: recordInterest(tag="Spring AI")
    ↓
InterestTools.recordInterest()   ← userId 从 ToolContext 取
    ↓
UserInterestService.record()
    ↓
已存在 → weight += 0.15（上限 1.0）；不存在 → weight = 0.3
redis.opsForHash().put("USER_INTEREST:" + fullUserId, tag, json)
```

### RETRIEVAL_PROFILE: 显式工具写（D51 新增）

```
用户："以后优先给我看财务部的"
    ↓
模型判断"这是检索偏好"
    ↓
返回 tool_call: saveRetrievalPreference(dimension="department", value="财务部")
    ↓
RetrievalPreferenceTools.saveRetrievalPreference()
    ↓
RetrievalProfileService.save()
    ↓
redis.opsForHash().putAll("RETRIEVAL_PROFILE:" + fullUserId, {...})
```

### CONV_MEM: 自动写（D50 新增）

```
用户请求 → ChatClient.stream()
    ↓
流式响应正常完成（doOnComplete）
    ↓
ConversationMemoryService.saveTurn(fullUserId, conversationId, turnIndex, userMsg, assistantMsg)
    ↓
conversationVectorStore.add(Document)
    ↓
Document 进入 conv-mem 向量索引
```

> **触发条件**：只在**流式响应正常结束**时保存——错误、中断、反问都不入档。

---

## 四、读取链路对比（六者在一次请求里怎么用）

```
用户请求："我那个退货的事怎么样了"
    ↓

① MessageChatMemoryAdvisor（order 最小）
   → 读 CHAT:hospital-a:user-alice:s2
   → 拿到本次会话的历史（本会话里可能没有退货相关内容）

② CompactingChatMemoryAdvisor（order=50）
   → 检查 CHAT 消息数是否超阈值（默认 100）→ 超则压缩

③ PreferenceAdvisor（order=100）
   → 读 USER_PREF:hospital-a:user-alice
   → 拿到 {city: 合肥, language: 中文}
   → 注入 SystemMessage："已知用户偏好：..."

④ UserInterestAdvisor（order=120）
   → 读 USER_INTEREST:hospital-a:user-alice
   → 按权重 TopN
   → 注入 SystemMessage："用户长期关注的技术领域：..."

⑤ RagAdvisor（order=150）
   → 使用前置检索结果（ChatService 已做一次检索）
   → 注入知识库资料

⑥ LongTermMemoryAdvisor（order=200）
   → 用 query 检索 LTM:hospital-a:user-alice（中文 2-gram 关键词）
   → "退货"命中
   → 注入 SystemMessage："相关历史记忆：用户订单1001退货，客服承诺3天内处理"

⑦ ConversationRetrievalAdvisor（order=210）
   → 向量检索 conv-mem（user_id=hospital-a_user_alice，排除当前会话）
   → "退货的事" 语义匹配到历史会话里的"订单1001退货"
   → 注入 SystemMessage："相关历史对话：..."

⑧ ConversationMemoryAdvisor（order=250，链尾）
   → 流式完成后写本轮对话到 conv-mem

    ↓

发给模型：
  System: 已知用户偏好：city=合肥...
          用户长期关注的技术领域：RAG、Spring AI...
          相关历史记忆：用户订单1001退货...
          相关历史对话：...
  History: [本会话历史]
  User: 我那个退货的事怎么样了

    ↓

模型回答："您订单1001的退货，客服承诺 3 天内处理..."
```

> **注意**：`RETRIEVAL_PROFILE` **不通过 Advisor 注入**——它在 `ChatService` 里被 `RetrievalProfileService.get(fullUserId)` 取出后，传给 `HybridSearchService` **调整融合权重**——影响检索结果，不直接进 Prompt。

---

## 五、六层记忆的核心区别

### CHAT vs CONV_MEM —— 同是"对话记录"，定位完全不同

| | CHAT（会话窗口） | CONV_MEM（对话向量） |
|---|---|---|
| 范围 | **当前会话** | **跨会话**（排除当前） |
| 检索 | 全量注入（最近 200 条） | 向量 Top-K |
| 时间 | 最近 N 条 | 180 天内全部 |
| 精度 | 原文 | 原文 |
| 类比 | 手里的速记本 | 索引化的历史档案 |

**关键差异**：

- CHAT 是**按会话隔离**的——`key = CHAT:{conversationId}`
- CONV_MEM 是**跨会话**的——过滤条件只按 `user_id`，**故意不带 sessionTag**

### 压缩 vs CONV_MEM —— 都解决"长会话"，角度不同

| | 压缩（CompactingAdvisor） | CONV_MEM（对话向量） |
|---|---|---|
| 目标 | 让长会话不爆 Token | 保留历史精确召回 |
| 结果 | **有损摘要** | **原文保留** |
| 时间跨度 | 当前会话 | 跨会话 |
| 检索 | 全量注入 | 语义 Top-K |

**关键差异**：

- 压缩是"整体摘要"——把 50 条压成 200 字，细节丢失
- CONV_MEM 是"逐轮精确存储"——支持精确召回原始那一轮

### LTM vs USER_INTEREST —— 都记"用户是什么样的人"，粒度不同

| | LTM（长期记忆） | USER_INTEREST（兴趣标签） |
|---|---|---|
| 结构 | 自由文本 | 标签 + 权重 |
| 检索 | 关键词匹配 | 权重 TopN |
| 更新 | 追加式 | 累加权重 |
| 类比 | 备忘录里的"一句话事实" | 用户画像上的"兴趣雷达图" |

### USER_PREF vs RETRIEVAL_PROFILE —— 都是"结构化偏好"，作用点不同

| | USER_PREF | RETRIEVAL_PROFILE |
|---|---|---|
| 作用 | **注入 Prompt**——影响回答风格 | **调整检索**——影响召回结果 |
| 举例 | `city=合肥` → 回答里考虑合肥 | `weight_bias_vec=+0.05` → 更依赖向量检索 |
| 谁读 | `PreferenceAdvisor` | `HybridSearchService` |
| 类比 | 告诉助手"我是谁" | 告诉助手"我要怎么查" |

---

## 六、为什么分六种，不分一种

如果全塞一个 key，会有这些问题：

| 问题 | 说明 |
|------|------|
| 检索方式冲突 | 会话要"最近 N 条"，长期记忆要"关键词匹配"，偏好要"全量读"，兴趣要"权重 TopN"，对话向量要"语义 Top-K"，画像要"调参" |
| 生命周期不同 | 会话 7 天，偏好 360 天，对话向量 180 天，画像 90 天 |
| 数据结构不同 | List 顺序，Hash 键值，向量索引语义 |
| 写入触发不同 | 会话和对话向量自动，LTM / USER_PREF / USER_INTEREST / RETRIEVAL_PROFILE 需要工具 |
| 注入时机不同 | 各自有 Advisor，按 order 依次执行 |
| 隔离范围不同 | CHAT 按会话隔离，其他按用户隔离 |
| 作用点不同 | 前 4 层注入 Prompt，检索画像调整检索参数 |

> **分六种 = 职责清晰，各司其职。**

---

## 七、用一个生活比喻

想象一个私人助理：

| 存储 | 类比 |
|------|------|
| CHAT | 他手里的**本次对话速记本**——记录"今天我们聊了什么" |
| LTM | 他桌上的**备忘录**——记下"老板上周说订单1001要退货" |
| USER_PREF | 他心里的**用户画像**——"老板常驻合肥、说中文" |
| USER_INTEREST | 他脑中的**兴趣雷达图**——"老板最近在研究 RAG 和 Agent" |
| CONV_MEM | 他柜子里的**历史档案**——按主题索引，随时能翻出"三个月前聊过类似的事" |
| RETRIEVAL_PROFILE | 他手里的**检索偏好手册**——"老板喜欢精确条款、优先查财务部的" |

每次你找他，他会：

1. 先看本次对话速记（知道刚刚聊到哪）
2. 再想用户画像（知道你是谁、你的习惯）
3. 再翻兴趣雷达图（知道你关注什么）
4. 再查备忘录（找出相关的重要事实）
5. 最后翻历史档案（找出"以前聊过的类似话题"）
6. 检索前先看偏好手册（决定怎么查更合你的口味）

---

## 八、六层配合的完整例子

**场景：用户开新会话，问跨会话的问题**

```
用户：「我上次说的那个采购项目，进展怎么样了」

后台发生的事：

① CHAT:hospital-a:user-alice:s3      → 本会话历史：空（新会话）
② USER_PREF:hospital-a:user-alice    → {city:合肥, job:采购主管}
③ USER_INTEREST:hospital-a:user-alice → RAG=0.5, Spring AI=0.3
④ LTM:hospital-a:user-alice          → 无"采购项目"相关记忆
⑤ RETRIEVAL_PROFILE:hospital-a:user-alice → preferred_departments=["采购部"], weight_bias_vec=+0.05
⑥ CONV_MEM                           → 向量匹配 "采购项目" ≈ 会话 s1 里的那一轮
                                        → 召回："我在做瑞丽市总医院的采购项目"

合并注入：
  System: "已知用户偏好：job=采购主管
          用户长期关注的技术领域：RAG、Spring AI
          相关历史对话：用户之前提到'我在做瑞丽市总医院的采购项目'"
  检索参数：优先召回采购部文档 + 更依赖向量检索

用户看到：「您上次提到的瑞丽市总医院采购项目...」
```

**如果 CONV_MEM 为空**：

```
用户看到：「我查不到您提到的项目，请提供更多信息」
```

---

## 九、生产环境建议：六者都加 TTL

| 存储 | 建议 TTL | 原因 |
|------|---------|------|
| CHAT | 7 天 | 会话过期后可丢 |
| LTM | 90~365 天 | 长期事实，但也不能永久堆积 |
| USER_PREF | 365 天+ | 偏好变化慢，可长存 |
| USER_INTEREST | 180 天 | 兴趣会变，半年后未命中自动衰减 |
| RETRIEVAL_PROFILE | 90 天 | 用户偏好检索方式也会变——比偏好更快 |
| CONV_MEM | 180 天 | 历史对话——医疗/政务场景需要较长追溯期 |

---

## 十、数据隔离层级——安全底线

```
租户 A 的所有数据               租户 B 的所有数据
        ↓                             ↓
tenantId = 'hospital-a'         tenantId = 'hospital-b'
        ↓                             ↓
┌─────────────────────────┐   ┌─────────────────────────┐
│ CHAT:hospital-a:*        │   │ CHAT:hospital-b:*        │
│ USER_PREF:hospital-a:*   │   │ USER_PREF:hospital-b:*   │
│ LTM:hospital-a:*         │   │ LTM:hospital-b:*         │
│ USER_INTEREST:hospital-a │   │ USER_INTEREST:hospital-b │
│ RETRIEVAL_PROFILE:...    │   │ RETRIEVAL_PROFILE:...    │
│ conv-mem (tenant_id=a)   │   │ conv-mem (tenant_id=b)   │
└─────────────────────────┘   └─────────────────────────┘
        ↑                             ↑
   物理隔离，代码层面无法跨租户访问
```

**CONV_MEM 的三重隔离**：

1. **租户隔离**——`tenant_id == 'hospital-a'` 强制过滤（安全底线）
2. **用户隔离**——`user_id == 'hospital-a_user_alice'` 强制过滤（安全底线）
3. **会话隔离**——`conversation_id != '{当前会话}'`（避免重复注入）
4. **语义隔离**——`similarity_threshold` 过滤低分结果

**其他五层的隔离**：

| 层 | 隔离方式 |
|----|---------|
| CHAT | `key = CHAT:{tenantId}:{userId}:{sessionTag}` |
| LTM | `key = LTM:{tenantId}:{userId}` |
| USER_PREF | `key = USER_PREF:{tenantId}:{userId}` |
| USER_INTEREST | `key = USER_INTEREST:{tenantId}:{userId}` |
| RETRIEVAL_PROFILE | `key = RETRIEVAL_PROFILE:{tenantId}:{userId}` |

---

## 十一、一句话总结

六层记忆各司其职：

**内容层**：

- **CHAT**：记录这次聊了什么（自动，短期，按会话）
- **LTM**：记录用户说过的重要事实（模型主动，长期，按用户）
- **USER_PREF**：记录用户的结构化偏好（模型主动，长期，按用户）
- **USER_INTEREST**：记录用户关注的技术领域（模型主动，中期，按用户）

**模式层**：

- **CONV_MEM**：记录跨会话的原始对话（自动，长期，按用户）
- **RETRIEVAL_PROFILE**：记录检索偏好参数（显式工具，中期，按用户）

**层次关系**：

- CHAT 和 CONV_MEM 都存对话——但一个按会话、一个跨会话
- 压缩和 CONV_MEM 都解决长会话——但一个是有损摘要、一个是精确原文
- LTM 和 USER_PREF 都是"模型主动写"——一个存事实、一个存偏好
- USER_PREF 和 RETRIEVAL_PROFILE 都是结构化——一个影响回答、一个影响检索
- USER_INTEREST 和 RETRIEVAL_PROFILE 都是"调参"——一个调推荐、一个调检索

**前 4 层分别注入 SystemMessage；第 5 层（CONV_MEM）注入历史对话；第 6 层（RETRIEVAL_PROFILE）不进 Prompt，直接调整检索权重。**

> **理解这六层，你就掌握了 Agent 记忆体系的完整框架。**