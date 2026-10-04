package org.example.rag.ingest.config;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.ingest.service.IncrementalUpdateService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 启动时异步扫描文档目录
 * <p>
 * 设计要点：
 * 1. 异步执行——不阻塞应用启动
 * 2. 延迟 30 秒——等依赖服务就绪 + 避免和 @Scheduled 撞车
 */
@Slf4j
@Configuration
@Order(100)
public class RagStartupRunner implements ApplicationRunner {

    private final IncrementalUpdateService incrementalUpdateService;

    public RagStartupRunner(IncrementalUpdateService incrementalUpdateService) {
        this.incrementalUpdateService = incrementalUpdateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ★ 异步执行，不阻塞主线程
        new Thread(() -> {
            try {
                // 延迟 15 秒——让应用完全启动，依赖服务就绪
                Thread.sleep(15_000);
                log.info("========== 启动后延迟扫描文档目录 ==========");
                incrementalUpdateService.scanAndUpdate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("启动扫描失败", e);
            }
        }, "rag-startup-scan").start();

        log.info("RagStartupRunner 已注册，将在 15 秒后异步扫描");
    }
}