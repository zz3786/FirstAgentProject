# Spring AI 三层记忆体系详解

## 一、三层记忆总览

| 维度 | CHAT（会话记忆） | LTM（长期记忆） | USER_PREF（用户偏好） |
|------|-----------------|----------------|----------------------|
| 存什么 | 本次会话的完整对话 | 跨会话的重要事实 | 结构化标签 |
| 数据结构 | Redis List（每条消息一个元素） | Redis Hash（field=时间戳，value=JSON） | Redis Hash（field=键，value=值） |
| 谁写 | RedisChatMemoryRepository | LongTermMemoryService | UserPreferenceService |
| 触发 | 自动（每轮对话） | 模型主动调工具 | 模型主动调工具 |
| 读取 | MessageChatMemoryAdvisor 自动 | MemoryRetrievalAdvisor 关键词检索 | PreferenceAdvisor 全量注入 |
| 生命周期 | TTL 7天，滑窗保留 20 条 | 永久（可加 TTL） | 永久（可加 TTL） |
| 典型内容 | "我叫小明"、"帮我算123+456" | "订单1001退货，客服承诺3天" | city=合肥、language=中文 |

---

## 二、Redis 里的实际结构对比

```
CHAT:eval-mem-001 (list)
  ├─ [0] {"type":"USER","textContent":"我叫小明",...}
  ├─ [1] {"type":"ASSISTANT","textContent":"你好小明",...}
  └─ [2] {"type":"USER","textContent":"我叫什么名字",...}

LTM:eval-ltm-001 (hash)
  └─ 1790060354427_0.061: {"content":"用户上周订单1001退货，客服承诺3天内处理","type":"FACT","time":"2026-09-22"}

USER_PREF:eval-pref-001 (hash)
  ├─ city: 合肥
  └─ language: 中文
```

### 三种数据结构的差异

| 存储 | 为什么用这种结构 |
|------|----------------|
| CHAT: List | 对话天然有序，需要按时间读取、滑窗裁剪 |
| LTM: Hash | 每条记忆独立，需要按 ID 增删、按内容检索 |
| USER_PREF: Hash | 键值对结构，city=合肥 一目了然，方便直接读某个字段 |

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

> 你从未手动调用过它，每轮对话自动触发。

### LTM: 模型主动写

```
用户："记住，我订单1001退货了"
    ↓
模型判断"这是重要事实"
    ↓
返回 tool_call: saveLongTermMemory(content="用户订单1001退货")
    ↓
MemoryTools.saveLongTermMemory()   ← 你的 @Tool 方法
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
PreferenceTools.savePreference()   ← 你的 @Tool 方法
    ↓
UserPreferenceService.save()
    ↓
redis.opsForHash().put("USER_PREF:" + userId, "city", "合肥")
```

---

## 四、读取链路对比（三者在一次请求里怎么用）

```
用户请求："我那个退货的事怎么样了"
    ↓

① MessageChatMemoryAdvisor
   → 读 CHAT:user-001:s2
   → 拿到本次会话的历史（本会话里没有退货相关内容）

② PreferenceAdvisor（order=100）
   → 读 USER_PREF:user-001
   → 拿到 {city: 合肥, language: 中文}
   → 注入 SystemMessage："已知用户偏好：city=合肥, language=中文"

③ MemoryRetrievalAdvisor（order=200）
   → 用 query="我那个退货的事怎么样了" 检索 LTM:user-001
   → 关键词 "退货" 命中
   → 注入 SystemMessage："相关历史记忆：用户订单1001退货，客服承诺3天内处理"

    ↓

发给模型：
  System: 已知用户偏好：city=合肥...
          相关历史记忆：用户订单1001退货...
  History: [本会话历史]
  User: 我那个退货的事怎么样了

    ↓

模型回答："您订单1001的退货，客服承诺 3 天内处理..."
```

---

## 五、为什么分三种，不分一种

如果全塞一个 key，会有这些问题：

| 问题 | 说明 |
|------|------|
| 检索方式冲突 | 会话要"最近 N 条"，长期记忆要"关键词匹配"，偏好要"全量读" |
| 生命周期不同 | 会话 7 天过期，偏好和记忆要长期保留 |
| 数据结构不同 | List 适合顺序，Hash 适合键值 |
| 写入触发不同 | 会话自动，另两个需要模型主动调工具 |
| 注入时机不同 | 会话走 Advisor 的 before/after，偏好和记忆都是 before |

> **分三种 = 职责清晰，各司其职。**

---

## 六、用一个生活比喻

想象一个私人助理：

| 存储 | 类比 |
|------|------|
| CHAT | 他手里的**本次对话速记本**——记录"今天我们聊了什么"，聊完就归档 |
| LTM | 他桌上的**备忘录**——记下"老板上周说订单1001要退货"这种重要事实 |
| USER_PREF | 他心里的**用户画像**——"老板常驻合肥、说中文、喜欢简洁回复" |

每次你找他，他会：
1. 先看本次对话速记（知道刚刚聊到哪）
2. 再想用户画像（知道你是谁、你的习惯）
3. 最后查备忘录（找出和你这次问题相关的事）

---

## 七、三者配合的完整例子

**场景：用户和新会话聊退货**

```
用户：「我那个退货的事怎么样了」

后台发生的事：

① CHAT:user-001:s2     → 本会话历史：空（新会话）
② USER_PREF:user-001   → {city:合肥}
③ LTM:user-001         → 关键词"退货"命中 → "订单1001退货，客服承诺3天"

合并注入：
  System: "已知用户偏好：city=合肥
          相关历史记忆：用户订单1001退货，客服承诺3天内处理"

用户看到：「您订单1001的退货...」
```

如果 LTM 为空：

```
用户看到：「我查不到进度，请提供订单号」
```

> 这就是你之前观察到的现象——LTM 没命中，模型自然不知道。

---

## 八、生产环境建议：三者都加 TTL

| 存储 | 建议 TTL | 原因 |
|------|---------|------|
| CHAT | 7 天 | 会话过期后可丢 |
| LTM | 90~365 天 | 长期事实，但也不能永久堆积 |
| USER_PREF | 365 天+ | 偏好变化慢，可长存 |

---

## 九、一句话总结

三层记忆各司其职：

- **CHAT**: 记录这次聊了什么（自动，短期）
- **LTM**: 记录用户说过的重要事实（模型主动，长期）
- **USER_PREF**: 记录用户的结构化偏好（模型主动，长期）

三者分别写入、分别读取、分别注入，最后合并进一次请求的 SystemMessage。

> **理解这三层，你就掌握了 Agent 记忆体系的完整框架。**
>
> 下一步把 `extractKeywords` 改成 2-gram，让 LTM 检索能命中中文——D19 就彻底完成。
