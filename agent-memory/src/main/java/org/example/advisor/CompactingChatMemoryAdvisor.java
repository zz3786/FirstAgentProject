package org.example.advisor;

import org.example.config.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话记忆压缩 Advisor
 * <p>
 * 作用：当某个会话的历史消息数超过阈值时，调用大模型把"旧消息"压缩成一段摘要，
 * 用摘要替换原文，避免滑动窗口把早期关键信息丢弃。
 * <p>
 * 执行顺序：必须在 MessageChatMemoryAdvisor 之后（order 更大），
 * 保证它看到的是已经写入 CHAT 的最新历史。
 */
public class CompactingChatMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CompactingChatMemoryAdvisor.class);

    /** 生成摘要用的轻量 ChatClient（不能带 MessageChatMemoryAdvisor，否则循环依赖） */
    private final ChatClient summarizerClient;

    /** 会话记忆，用于读写 CHAT */
    private final ChatMemory chatMemory;

    /** 压缩配置 */
    private final CompactionConfig config;

    /**
     * @param chatMemory       会话记忆
     * @param summarizerClient 摘要生成客户端（独立于主 Client）
     * @param config           压缩配置
     */
    public CompactingChatMemoryAdvisor(ChatMemory chatMemory,
                                       ChatClient summarizerClient,
                                       CompactionConfig config) {
        this.chatMemory = chatMemory;
        this.summarizerClient = summarizerClient;
        this.config = config;
    }

    @Override
    public String getName() {
        return "CompactingChatMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // 50：在 MessageChatMemoryAdvisor 之后、PreferenceAdvisor(100) 之前
        return 50;
    }

    // ===================== 同步调用 =====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        compactIfNeeded(request);
        return chain.nextCall(request);
    }

    // ===================== 流式调用 =====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        compactIfNeeded(request);
        return chain.nextStream(request);
    }

    // ===================== 核心逻辑 =====================
    private void compactIfNeeded(ChatClientRequest request) {
        // 1. 拿会话 ID
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return;
        }
        String conversationId = cid.toString();

        // 2. 读取当前会话历史（✅ 修正点：用 get 而不是 findByConversationId）
        List<Message> history = chatMemory.get(conversationId);

        if (history == null || history.size() < config.getMaxMessagesBeforeCompaction()) {
            return;
        }

        log.info("Compacting 触发: cid={}, 消息数={}, 阈值={}",
                conversationId, history.size(), config.getMaxMessagesBeforeCompaction());

        try {
            int keepCount = config.getKeepRecentMessages();
            int compressEndIndex = history.size() - keepCount;
            List<Message> toCompress = history.subList(0, compressEndIndex);
            List<Message> toKeep = history.subList(compressEndIndex, history.size());

            String summary = summarize(toCompress);
            log.info("Compacting 摘要生成完成: cid={}, 压缩 {} 条 → 摘要 {} 字",
                    conversationId, toCompress.size(), summary.length());

            List<Message> compacted = new ArrayList<>();
            compacted.add(new SystemMessage("[历史对话摘要] " + summary));
            compacted.addAll(toKeep);

            // 3. 覆盖写回
            chatMemory.clear(conversationId);
            chatMemory.add(conversationId, compacted);   // ← 若报错，改成 .toArray(new Message[0])

            log.info("Compacting 完成: cid={}, 压缩后消息数={}",
                    conversationId, compacted.size());

        } catch (Exception e) {
            log.error("Compacting 失败: cid={}", conversationId, e);
        }
    }

    /**
     * 调用大模型把一段对话压缩成摘要
     */
    private String summarize(List<Message> messages) {
        String text = messages.stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .collect(Collectors.joining("\n"));

        String prompt = """
                请将下面这段对话压缩为简洁的要点摘要。
                要求：
                1. 保留所有关键事实（人名、订单号、承诺、偏好等）
                2. 丢弃寒暄、重复、无信息量的内容
                3. 用第三人称描述
                4. 不超过 200 字

                对话内容：
                %s
                """.formatted(text);

        return summarizerClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}