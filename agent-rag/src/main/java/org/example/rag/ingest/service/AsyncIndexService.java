package org.example.rag.ingest.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.ingest.config.AsyncIndexProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D55 异步索引服务
 * <p>
 * <b>职责</b>：把"一批文件"并发提交给线程池——每个 worker 调 {@link DocumentIngestService#ingest}。
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li><b>有界并发</b>——线程池 maxPoolSize = concurrency</li>
 *   <li><b>限流</b>——Semaphore 限制 embedding API 同时调用数</li>
 *   <li><b>失败隔离</b>——每个文件独立 CompletableFuture——一个挂不影响其他</li>
 *   <li><b>进度可观测</b>——每 N 个打日志</li>
 *   <li><b>优雅关闭</b>——由线程池的 waitForTasksToCompleteOnShutdown 保证</li>
 * </ul>
 * <p>
 * <b>为什么不用 @Async</b>：
 * @Async 的线程池是全局共享的——和你项目里其他异步任务（如 RagStartupRunner 自己 new Thread）
 * 会互相影响。独立线程池隔离。
 */
@Slf4j
@Service
public class AsyncIndexService {

    private final DocumentIngestService ingestService;
    private final ThreadPoolTaskExecutor executor;
    private final AsyncIndexProperties props;

    /**
     * 限流信号量——同时调 embedding API 的任务数上限
     * <p>
     * 目的：避免触发 API 的 QPS 限流（429）。
     * 取值 = concurrency——一般够用。
     */
    private final Semaphore embeddingLimiter;

    public AsyncIndexService(DocumentIngestService ingestService,
                             ThreadPoolTaskExecutor asyncIndexExecutor,
                             AsyncIndexProperties props) {
        this.ingestService = ingestService;
        this.executor = asyncIndexExecutor;
        this.props = props;
        this.embeddingLimiter = new Semaphore(Math.max(1, props.getConcurrency()));
    }

    @PostConstruct
    public void printConfig() {
        log.info("[D55] AsyncIndexService 初始化: enabled={}, concurrency={}, queueCapacity={}, timeoutPerFile={}s",
                props.isEnabled(), props.getConcurrency(),
                props.getQueueCapacity(), props.getPerFileTimeoutSeconds());
    }

    // ==================== 核心：批量异步入库 ====================

    /**
     * 批量异步入库
     * <p>
     * <b>返回时机</b>：所有文件处理完成后（成功 / 失败都返回）——调用方可以拿结果汇总。
     * <p>
     * <b>超时</b>：单文件超时由 {@code perFileTimeoutSeconds} 控制——超时的文件计入 failed。
     *
     * @param files 待入库的文件列表
     * @return 入库结果汇总
     */
    public BatchResult ingestBatch(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return new BatchResult(0, 0, 0, List.of());
        }

        if (!props.isEnabled()) {
            log.info("[D55] 异步索引未启用——退化为同步");
            return ingestSync(files);
        }

        long startTime = System.currentTimeMillis();
        int total = files.size();

        log.info("[D55] 批量异步入库开始: 文件数={}, 并发={}", total, props.getConcurrency());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);
        List<String> failedFiles = new ArrayList<>();

        // 提交所有任务——每个文件一个 CompletableFuture
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);

        for (Path file : files) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                long fileStart = System.currentTimeMillis();
                String fileName = file.getFileName().toString();

                try {
                    // ★ 限流——限制 embedding API 并发
                    boolean acquired = embeddingLimiter.tryAcquire(
                            props.getPerFileTimeoutSeconds(), TimeUnit.SECONDS);
                    if (!acquired) {
                        log.warn("[D55] 等待 embedding 限流信号超时——跳过: {}", fileName);
                        failedCount.incrementAndGet();
                        synchronized (failedFiles) {
                            failedFiles.add(fileName + "（等待限流超时）");
                        }
                        return;
                    }

                    try {
                        // ★ 最小间隔——防止 API 429
                        if (props.getMinIntervalMs() > 0) {
                            Thread.sleep(props.getMinIntervalMs());
                        }

                        // ★ 执行入库
                        ingestService.ingestWithFingerprint(file);

                        successCount.incrementAndGet();

                    } finally {
                        embeddingLimiter.release();
                    }

                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    synchronized (failedFiles) {
                        failedFiles.add(fileName + "（" + e.getMessage() + "）");
                    }
                    log.error("[D55] 文件入库失败: {}", file, e);
                } finally {
                    int processed = processedCount.incrementAndGet();

                    // ★ 进度日志
                    if (processed % props.getProgressLogInterval() == 0
                            || processed == total) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        double rate = processed * 1000.0 / Math.max(1, elapsed);
                        log.info("[D55] 进度: {}/{} ({}%), 成功={}, 失败={}, 耗时={}ms, 速率={}/s",
                                processed, total, processed * 100 / total,
                                successCount.get(), failedCount.get(),
                                elapsed, String.format("%.2f", rate));
                    }

                    long fileCost = System.currentTimeMillis() - fileStart;
                    log.debug("[D55] 文件完成: {} (耗时 {}ms)", fileName, fileCost);
                }
            }, executor);

            futures.add(future);
        }

        // ★ 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long totalCost = System.currentTimeMillis() - startTime;
        log.info("[D55] 批量异步入库完成: 总数={}, 成功={}, 失败={}, 总耗时={}ms",
                total, successCount.get(), failedCount.get(), totalCost);

        return new BatchResult(total, successCount.get(), failedCount.get(), failedFiles);
    }

    // ==================== 同步退化 ====================

    /**
     * 同步执行（异步被关闭时的降级）
     */
    private BatchResult ingestSync(List<Path> files) {
        int success = 0;
        int failed = 0;
        List<String> failedFiles = new ArrayList<>();

        for (Path file : files) {
            try {
                ingestService.ingest(new FileSystemResource(file));
                success++;
            } catch (Exception e) {
                failed++;
                failedFiles.add(file.getFileName() + "（" + e.getMessage() + "）");
                log.error("同步入库失败: {}", file, e);
            }
        }
        return new BatchResult(files.size(), success, failed, failedFiles);
    }

    // ==================== 结果 ====================

    /**
     * 批量入库结果
     */
    public record BatchResult(
            int total,
            int success,
            int failed,
            List<String> failedFiles
    ) {
        public boolean hasFailure() {
            return failed > 0;
        }

        public String summary() {
            return String.format("总数 %d, 成功 %d, 失败 %d", total, success, failed);
        }
    }
}