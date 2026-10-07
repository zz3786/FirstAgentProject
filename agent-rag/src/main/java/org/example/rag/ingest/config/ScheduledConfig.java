package org.example.rag.ingest.config;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.ingest.service.IncrementalUpdateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时扫描配置
 *
 * <h3>修复说明（D66 补丁）</h3>
 * <p>
 * 原来读的 key 是 {@code app.rag.scan-interval-ms}，
 * 但 {@link IngestProperties} 的前缀是 {@code app.rag.ingest}，
 * 对应的 key 是 {@code app.rag.ingest.scan-interval-ms}。
 * key 不匹配 → Spring 用默认值 60000ms（1 分钟）。
 *
 * <p>本类修正后，读取 {@code app.rag.ingest.scan-interval-ms}，
 * 与 IngestProperties 保持一致。同时修正
 * {@code @ConditionalOnProperty} 的 key（同样少了一层 {@code ingest}）。
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.rag.ingest.incremental-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ScheduledConfig {

    private final IncrementalUpdateService incrementalUpdateService;

    public ScheduledConfig(IncrementalUpdateService incrementalUpdateService) {
        this.incrementalUpdateService = incrementalUpdateService;
    }

    @Scheduled(
            // ★ 修正：两个 key 都要加 ingest
            initialDelayString = "${app.rag.ingest.scan-interval-ms:60000}",
            fixedDelayString = "${app.rag.ingest.scan-interval-ms:60000}"
    )
    public void scanDirectory() {
        try {
            incrementalUpdateService.scanAndUpdate();
        } catch (Exception e) {
            log.error("定时扫描失败", e);
        }
    }
}