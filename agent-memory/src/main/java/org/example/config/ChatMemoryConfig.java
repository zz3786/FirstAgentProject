package org.example.config;


import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatMemoryConfig {

    @Bean("inMemoryChatMemory")
    public ChatMemory inMemoryChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
    }

    /**
     * 现在如果CHAT里面 eval-tool-001 有200条记录了 会怎么样?
     * AI解答:
     * 200 条意味着什么
     * maxMessages(200) = 最多保留 200 条消息。
     * 按"1 轮对话 = 1 user + 1 assistant = 2 条"算：
     *
     * ① 用户："第101轮的问题"
     *     ↓
     * ② MessageChatMemoryAdvisor.before()
     *     → 读 CHAT:eval-tool-001 = [1~200 条]
     *     → 注入 Prompt
     *     ↓
     * ③ 模型回答："第101轮的回答"
     *     ↓
     * ④ MessageChatMemoryAdvisor.after()
     *     → combined = [1~200 条] + [新 user, 新 assistant] = 202 条
     *     → 超过 200 → 裁剪
     *     → 保留最近 200 条 = [3~202 条]   ← 最旧的 2 条（第1轮）被丢弃
     *     ↓
     * ⑤ RedisChatMemoryRepository.saveAll()
     *     → delete key
     *     → 写入新的 200 条
     *
     *  CompactingChatMemoryAdvisor解决什么问题?
     *  核心思路：在消息被窗口丢弃之前，先把它们压缩成摘要保留下来
     *
     *  滑动窗口满了（200条）：
     *     [u1, a1, u2, a2, ..., u100, a100]
     *     ↓ CompactingChatMemoryAdvisor 触发
     * 压缩最旧的 50 条 → 一段摘要
     *     ↓ 摘要替换这 50 条
     * [u1~u50摘要] + [u51, a51, ..., u100, a100] = 151 条
     *     ↓ 继续对话
     *     ↓ 又满了 → 再压缩
     * [新摘要] + [u101, a101, ..., u150, a150]
     * @param repo
     * @return
     */
    @Bean("redisChatMemory")
    @Primary  //带 @Primary，意味着如果只有一个 ChatMemory 类型，就注入它。如果有多个，@Primary 的优先
    public ChatMemory redisChatMemory(
            @Qualifier("redisChatMemoryRepository") ChatMemoryRepository repo) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(200) //应为配置文件里配置 100条就开始压缩了 上限永远到不了200
                .build();
    }
}