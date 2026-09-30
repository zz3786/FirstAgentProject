package org.example.cache.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * EmbeddingModel 装饰器 —— 相同文本的 embedding 结果本地缓存
 * <p>
 * <b>为什么用 Caffeine 而非 ConcurrentHashMap</b>：
 * 1. 有上限（maximumSize）→ 不会内存泄漏
 * 2. 有 TTL（expireAfterWrite）→ 模型升级后旧缓存会自动过期
 * 3. LRU 淘汰 → 热点数据优先保留
 * 4. 统计信息（recordStats）→ 可观测命中率
 * <p>
 * <b>容量估算</b>：
 * 每条 float[1024] ≈ 4KB（不含对象头和 key），
 * 10000 条 ≈ 40MB —— 一个安全的上限。
 * 若你的 embedding 维度更大（如 3072 维），按比例缩小。
 */
@Slf4j
@Component
@Primary
public class CachedEmbeddingModel implements EmbeddingModel {

    /** 缓存最大容量（条数） */
    private static final int MAX_SIZE = 10_000;

    /** 缓存 TTL：7 天。模型升级/配置变更后旧缓存自动失效 */
    private static final Duration TTL = Duration.ofDays(7);

    private final EmbeddingModel delegate;

    private final Cache<String, float[]> cache;

    public CachedEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(TTL)
                .recordStats()              // 开启统计，可打印命中率
                .build();
    }

    // ==================== 核心拦截点 1：call() ====================

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();

        // 批量请求直接透传（缓存对批量无意义）
        if (inputs.size() != 1) {
            return delegate.call(request);
        }

        String text = inputs.get(0);

        // Caffeine 的 get(key, mappingFunction) 是原子操作：
        //   - 命中：返回缓存值，不调 mappingFunction
        //   - 未命中：调 mappingFunction 计算，并自动写入缓存
        float[] embedding = cache.get(text, k -> {
            log.debug("embedding 未命中，调用 API: [{}]", truncate(k, 30));
            EmbeddingResponse resp = delegate.call(
                    new EmbeddingRequest(List.of(k), request.getOptions()));
            return resp.getResult().getOutput();
        });

        return buildResponse(embedding);
    }

    // ==================== 核心拦截点 2：embed(Document) ====================

    @Override
    public float[] embed(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return delegate.embed(document);
        }

        return cache.get(text, k -> {
            log.debug("embedding 未命中(embedDocument)，调用 API: [{}]", truncate(k, 30));
            return delegate.embed(document);
        });
    }

    // ==================== 辅助 ====================

    /**
     * 打印缓存统计（可挂在定时任务上观测命中率）
     */
    public void logStats() {
        var stats = cache.stats();
        log.info("Embedding 缓存统计: size={}, hitRate={}, hitCount={}, missCount={}",
                cache.estimatedSize(),
                String.format("%.2f%%", stats.hitRate() * 100),
                stats.hitCount(),
                stats.missCount());
    }

    private EmbeddingResponse buildResponse(float[] embedding) {
        var result = new Embedding(embedding, 0);
        return new EmbeddingResponse(List.of(result));
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}