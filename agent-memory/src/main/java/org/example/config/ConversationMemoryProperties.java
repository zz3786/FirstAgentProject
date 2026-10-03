package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D50 对话历史向量化配置
 * <p>
 * 与 {@code SemanticCacheProperties} 分离——它们是两个独立的 Redis 索引，
 * 生命周期、TTL 策略、相似度阈值完全不同，不要合在一起。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.conversation-memory")
public class ConversationMemoryProperties {

    /** 总开关——测试 / 故障时快速关闭 */
    private boolean enabled = true;

    /** Redis 向量索引名（与 semantic-cache-index 完全隔离） */
    private String indexName = "conversation-memory-index";

    /** Redis key 前缀 */
    private String keyPrefix = "conv-mem:";

    /** 检索返回条数 */
    private int topK = 3;

    /**
     * 相似度阈值
     * <p>
     * ★ 比 RAG 的 0.5 高——对话历史是"锦上添花"，
     * 召回错了会污染 prompt 让模型混乱，比召回不到更糟。
     */
    private double similarityThreshold = 0.75;

    /** assistant 回答入库时的最大字符数（防止超长回答撑爆向量 + 存储膨胀） */
    private int maxAssistantChars = 2000;

    /** 用户消息最小长度——太短的（"嗯"、"好"）不入库，避免噪声 */
    private int minUserMessageChars = 5;

    /** 是否排除当前会话（true = 只检索跨会话历史） */
    private boolean excludeCurrentConversation = false;

    // ==================== ★ D54：SCAN 查询配置 ====================

    /** SCAN 每批返回数量——建议 100~1000 */
    private int scanBatchSize = 500;

    /** SCAN 总超时（秒）——防止 Redis 慢时无限循环 */
    private int scanTimeoutSeconds = 5;

    /** 单次查询最大返回——防止恶意调用拉爆 */
    private int maxConversationIds = 1000;
}