package org.example.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.IncrementalUpdateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时任务配置
 * <p>
 * 每 scanIntervalMs 扫描一次文档目录
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.rag.incremental-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ScheduledConfig {

    private final IncrementalUpdateService incrementalUpdateService;

    public ScheduledConfig(IncrementalUpdateService incrementalUpdateService) {
        this.incrementalUpdateService = incrementalUpdateService;
    }

    @Scheduled(
            initialDelayString = "${app.rag.scan-interval-ms:60000}",   // ★ 首次延迟 60 秒
            fixedDelayString = "${app.rag.scan-interval-ms:60000}"
    )
    public void scanDirectory() {
        try {
            incrementalUpdateService.scanAndUpdate();
        } catch (Exception e) {
            log.error("定时扫描失败", e);
        }
    }
}