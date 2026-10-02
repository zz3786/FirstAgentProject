# Spring AI 四层记忆体系详解

## 一、四层记忆总览

| 维度 | CHAT（会话记忆） | LTM（长期记忆） | USER_PREF（用户偏好） | CONV_MEM（对话向量） |
|------|-----------------|----------------|----------------------|---------------------|
| 存什么 | 本次会话的完整对话 | 跨会话的重要事实 | 结构化标签 | 跨会话的原始对话轮次 |
| 数据结构 | Redis List | Redis Hash | Redis Hash | Redis 向量索引 |
| 谁写 | RedisChatMemoryRepository | LongTermMemoryService | UserPreferenceService | ConversationMemoryService |
| 触发 | 自动（每轮对话） | 模型主动调工具 | 模型主动调工具 | 自动（每轮对话） |
| 读取 | MessageChatMemoryAdvisor 自动 | MemoryRetrievalAdvisor 关键词检索 | PreferenceAdvisor 全量注入 | ConversationRetrievalAdvisor 向量检索 |
| 检索方式 | 滑动窗口（最近 N 条） | 中文 2-gram 关键词 | 按 key 直读 | 向量语义相似度 |
| 范围 | 当前会话 | 当前用户 | 当前用户 | **当前用户 + 排除当前会话** |
| 生命周期 | TTL 7 天，窗口 200 条 | TTL 180 天 | TTL 360 天 | TTL 180 天 |
| 典型内容 | "我叫小明"、"帮我算123+456" | "订单1001退货，客服承诺3天" | city=合肥、language=中文 | "上次聊的采购项目进展" |

---

## 二、Redis 里的实际结构对比

```
CHAT:user-alice:s1 (list)
  ├─ [0] {"type":"USER","textContent":"我叫小明",...}
  ├─ [1] {"type":"ASSISTANT","textContent":"你好小明",...}
  └─ [2] {"type":"USER","textContent":"我叫什么名字",...}

LTM:user-alice (hash)
  └─ 1790060354427_0.061: {"content":"用户上周订单1001退货，客服承诺3天内处理","type":"FACT","time":"2026-09-22"}

USER_PREF:user-alice (hash)
  ├─ city: 合肥
  └─ language: 中文

conv-mem:<UUID> (向量索引条目)
  ├─ text: "我那个退货的事怎么样了"
  └─ metadata:
       user_id       = "user-alice"
       conversation_id = "user-alice:s2"
       turn_index    = 5
       user_message  = "我那个退货的事怎么样了"
       assistant_message = "您订单1001的退货..."
       timestamp     = 1790060400000

conv-turn:user-alice:s2 (string)
  └─ "5"    ← 该会话已进行的轮次数
```

### 四种数据结构的差异

| 存储 | 为什么用这种结构 |
|------|----------------|
| CHAT: List | 对话天然有序，需要按时间读取、滑窗裁剪 |
| LTM: Hash | 每条记忆独立，需要按 ID 增删、按内容检索 |
| USER_PREF: Hash | 键值对结构，city=合肥 一目了然，方便直接读某个字段 |
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
返回 tool_call: saveLongTermMemory(content="用户订单1001退货")
    ↓
MemoryTools.saveLongTermMemory()
    ↓
LongTermMemoryService.save()
    ↓
redis.opsForHash().put("LTM:" + userId, id, json)
```

### USER_PREF: 模型主动写

```
用户："我常住在合肥"
    ↓
模型判断"这是一个可结构化的偏好"
    ↓
返回 tool_call: savePreference(key="city", value="合肥")
    ↓
PreferenceTools.savePreference()
    ↓
UserPreferenceService.save()
    ↓
redis.opsForHash().put("USER_PREF:" + userId, "city", "合肥")
```

### CONV_MEM: 自动写（D50 新增）

```
用户请求 → ChatClient.stream()
    ↓
流式响应正常完成（doOnComplete）
    ↓
ConversationMemoryService.saveTurn(userId, conversationId, turnIndex, userMsg, assistantMsg)
    ↓
conversationVectorStore.add(Document)
    ↓
Document 进入 conv-mem 向量索引
```

> **触发条件**：只在**流式响应正常结束**时保存——错误、中断、反问都不入档。

---

## 四、读取链路对比（四者在一次请求里怎么用）

```
用户请求："我那个退货的事怎么样了"
    ↓

① MessageChatMemoryAdvisor（order 最小）
   → 读 CHAT:user-alice:s2
   → 拿到本次会话的历史（本会话里可能没有退货相关内容）

② PreferenceAdvisor（order=100）
   → 读 USER_PREF:user-alice
   → 拿到 {city: 合肥, language: 中文}
   → 注入 SystemMessage："已知用户偏好：city=合肥, language=中文"

③ RagAdvisor（order=150）
   → 检索知识库（Qdrant + MySQL）
   → 注入相关文档片段

④ MemoryRetrievalAdvisor（order=200）
   → 用 query 检索 LTM:user-alice（中文 2-gram 关键词）
   → "退货"命中
   → 注入 SystemMessage："相关历史记忆：用户订单1001退货，客服承诺3天内处理"

⑤ ConversationRetrievalAdvisor（order=250）
   → 向量检索 conv-mem（user_id=user-alice，排除当前会话）
   → "退货的事" 语义匹配到历史会话里的"订单1001退货"
   → 注入 SystemMessage："相关历史对话：..."

    ↓

发给模型：
  System: 已知用户偏好：city=合肥...
          相关历史记忆：用户订单1001退货...
          相关历史对话：...
  History: [本会话历史]
  User: 我那个退货的事怎么样了

    ↓

模型回答："您订单1001的退货，客服承诺 3 天内处理..."
```

---

## 五、四层记忆的核心区别

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

---

## 六、为什么分四种，不分一种

如果全塞一个 key，会有这些问题：

| 问题 | 说明 |
|------|------|
| 检索方式冲突 | 会话要"最近 N 条"，长期记忆要"关键词匹配"，偏好要"全量读"，对话向量要"语义 Top-K" |
| 生命周期不同 | 会话 7 天过期，偏好 360 天，对话向量 180 天 |
| 数据结构不同 | List 适合顺序，Hash 适合键值，向量索引适合语义 |
| 写入触发不同 | 会话和对话向量自动，LTM 和 USER_PREF 需要模型主动调工具 |
| 注入时机不同 | 四层各有 Advisor，按 order 依次执行 |
| 隔离范围不同 | CHAT 按会话隔离，其他三层按用户隔离 |

> **分四种 = 职责清晰，各司其职。**

---

## 七、用一个生活比喻

想象一个私人助理：

| 存储 | 类比 |
|------|------|
| CHAT | 他手里的**本次对话速记本**——记录"今天我们聊了什么" |
| LTM | 他桌上的**备忘录**——记下"老板上周说订单1001要退货" |
| USER_PREF | 他心里的**用户画像**——"老板常驻合肥、说中文" |
| CONV_MEM | 他柜子里的**历史档案**——按主题索引，随时能翻出"三个月前聊过类似的事" |

每次你找他，他会：
1. 先看本次对话速记（知道刚刚聊到哪）
2. 再想用户画像（知道你是谁、你的习惯）
3. 再查备忘录（找出相关的重要事实）
4. 最后翻历史档案（找出"以前聊过的类似话题"）

---

## 八、四层配合的完整例子

**场景：用户开新会话，问跨会话的问题**

```
用户：「我上次说的那个采购项目，进展怎么样了」

后台发生的事：

① CHAT:user-alice:s3   → 本会话历史：空（新会话）
② USER_PREF:user-alice → {city:合肥, job:采购主管}
③ LTM:user-alice       → 无"采购项目"相关记忆
④ CONV_MEM             → 向量匹配 "采购项目" ≈ 会话 s1 里的那一轮
                          → 召回："我在做瑞丽市总医院的采购项目"

合并注入：
  System: "已知用户偏好：job=采购主管
          相关历史对话：用户之前提到'我在做瑞丽市总医院的采购项目'"

用户看到：「您上次提到的瑞丽市总医院采购项目...」
```

**如果 CONV_MEM 为空**：
```
用户看到：「我查不到您提到的项目，请提供更多信息」
```

---

## 九、生产环境建议：四者都加 TTL

| 存储 | 建议 TTL | 原因 |
|------|---------|------|
| CHAT | 7 天 | 会话过期后可丢 |
| LTM | 90~365 天 | 长期事实，但也不能永久堆积 |
| USER_PREF | 365 天+ | 偏好变化慢，可长存 |
| CONV_MEM | 180 天 | 历史对话——医疗/政务场景需要较长追溯期 |

---

## 十、数据隔离层级——安全底线

```
用户 A 的四层数据              用户 B 的四层数据
        ↓                             ↓
   user_id = 'A'                 user_id = 'B'
        ↓                             ↓
┌─────────────────┐           ┌─────────────────┐
│ CHAT:A:*        │           │ CHAT:B:*        │
│ USER_PREF:A     │           │ USER_PREF:B     │
│ LTM:A           │           │ LTM:B           │
│ CONV_MEM (A)    │           │ CONV_MEM (B)    │
└─────────────────┘           └─────────────────┘
        ↑                             ↑
   物理隔离，代码层面无法跨用户访问
```

**CONV_MEM 的三重隔离**：

1. **用户隔离**——`user_id == 'A'` 强制过滤（安全底线）
2. **会话隔离**——`conversation_id != '{当前会话}'`（避免重复注入）
3. **语义隔离**——`similarity_threshold` 过滤低分结果

---

## 十一、一句话总结

四层记忆各司其职：

- **CHAT**：记录这次聊了什么（自动，短期，按会话）
- **LTM**：记录用户说过的重要事实（模型主动，长期，按用户）
- **USER_PREF**：记录用户的结构化偏好（模型主动，长期，按用户）
- **CONV_MEM**：记录跨会话的原始对话（自动，长期，按用户）

**四层关系**：

- CHAT 和 CONV_MEM 都存对话——但一个按会话、一个跨会话
- 压缩和 CONV_MEM 都解决长会话——但一个是有损摘要、一个是精确原文
- LTM 和 USER_PREF 都需要模型主动写——一个存事实、一个存偏好

**四者分别写入、分别读取、分别注入，最后合并进一次请求的 SystemMessage。**

> **理解这四层，你就掌握了 Agent 记忆体系的完整框架。**