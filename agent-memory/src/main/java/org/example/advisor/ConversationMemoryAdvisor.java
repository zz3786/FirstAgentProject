package org.example.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.memory.ConversationMemoryService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * D50 对话历史写入 Advisor（写路径）
 * <p>
 * <b>执行顺序</b>：order = 250（链尾）
 * <p>
 * <b>为什么放在 Advisor 而不放 ChatService</b>：
 * <ol>
 *   <li>天然拿到完整的 user + assistant 消息，无需手动传递</li>
 *   <li>不污染 ChatService 业务逻辑（它只管编排，不管记忆）</li>
 *   <li>与 MessageChatMemoryAdvisor 职责对齐——一个存短期（CHAT），一个存长期（向量）</li>
 * </ol>
 * <p>
 * <b>turnIndex 为什么用 Redis INCR</b>：
 * 单机 AtomicInteger 重启后计数错乱，多实例部署更会冲突。
 * Redis INCR 是原子的，天然支持分布式。
 */
@Slf4j
public class ConversationMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String TURN_COUNTER_PREFIX = "conv-turn:";
    private static final Duration COUNTER_TTL = Duration.ofDays(7);

    private final ConversationMemoryService memoryService;
    private final StringRedisTemplate redis;

    public ConversationMemoryAdvisor(ConversationMemoryService memoryService,
                                     StringRedisTemplate redis) {
        this.memoryService = memoryService;
        this.redis = redis;
    }

    @Override
    public String getName() {
        return "ConversationMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        // 链尾——所有 Advisor 完成后才写历史
        return 250;
    }

    // ==================== 同步 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        persistTurn(request, extractAnswer(response));
        return response;
    }

    // ==================== 流式 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("[ENTER] {} order={}", getName(), getOrder());
        StringBuilder answerBuf = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(resp -> accumulate(resp, answerBuf))
                .doOnComplete(() -> {
                    log.info("[EXIT]  {} order={} —— 开始写对话历史", getName(), getOrder());
                    persistTurn(request, answerBuf.toString());
                })
                .doOnError(err -> log.warn("[ERROR] {} : {}", getName(), err.getMessage()));
    }

    // ==================== 核心：写入历史 ====================

    private void persistTurn(ChatClientRequest request, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }

        // ① 拿会话 ID
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        if (cid == null) {
            return;
        }
        String conversationId = cid.toString();
        String userId = extractUserId(conversationId);

        // ② 取最后一条用户消息
        String userMsg = lastUserMessage(request);
        if (userMsg == null || userMsg.isBlank()) {
            return;
        }

        // ③ 生成 turnIndex（Redis INCR）
        long turnIndex;
        try {
            String counterKey = TURN_COUNTER_PREFIX + conversationId;
            Long v = redis.opsForValue().increment(counterKey);
            turnIndex = v == null ? 0L : v;
            redis.expire(counterKey, COUNTER_TTL);
        } catch (Exception e) {
            // INCR 失败就用时间戳兜底——保证不丢对话
            turnIndex = System.currentTimeMillis();
            log.warn("turn 计数失败，用时间戳兜底: cid={}", conversationId, e);
        }

        // ④ 委托 Service 写库
        memoryService.saveTurn(userId, conversationId, turnIndex, userMsg, answer);
    }

    // ==================== 辅助 ====================

    private void accumulate(ChatClientResponse response, StringBuilder buffer) {
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return;
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        if (text != null) {
            buffer.append(text);
        }
    }

    private String extractAnswer(ChatClientResponse response) {
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    private String lastUserMessage(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse(null);
    }

    private String extractUserId(String conversationId) {
        int idx = conversationId.indexOf(':');
        return idx > 0 ? conversationId.substring(0, idx) : conversationId;
    }
}