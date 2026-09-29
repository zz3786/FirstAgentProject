package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.example.rag.config.OcrProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;

/**
 * OCR 核心服务
 * <p>
 * <b>职责</b>：把一张 BufferedImage 转成文本。
 * <b>不负责</b>：图片从哪来（文件 / PDF 页）、文本怎么入库。
 * —— 这两个职责分别属于 ImageDocumentReader 和 DocumentIngestService。
 *
 * <h3>三个关键设计决策</h3>
 *
 * <b>① ThreadLocal 而非共享实例</b>
 * Tesseract 的 native 句柄不是线程安全的。多线程共享一个实例，
 * 轻则结果错乱，重则 JVM 段错误（native crash，无法被 try-catch）。
 * ThreadLocal 为每个线程维护独立实例，是最轻量的隔离方案。
 *
 * <b>② Future + 超时中断</b>
 * Tesseract 遇到异常大图/损坏图可能不返回。用 Future.get(timeout)
 * 强制等待上限，超时后 cancel(true) 中断线程。
 *
 * <b>③ 结果清洗与过滤</b>
 * 原始 OCR 输出含大量噪声（空行、连续空格、误识别符号）。
 * 直接入向量库会污染检索结果，必须清洗 + 长度过滤。
 */
@Slf4j
@Service
public class OcrService {

    private final OcrProperties props;

    /**
     * 每线程独立的 Tesseract 实例
     * <p>
     * withInitial 保证线程首次调用 get() 时懒创建，
     * 不必在构造时批量创建（此时还不知道有多少工作线程）。
     */
    private final ThreadLocal<ITesseract> tesseractHolder;

    /**
     * 超时控制线程池
     * <p>
     * 线程数 = CPU 核数 / 2（OCR 是 CPU 密集型，
     * 开太多反而因为上下文切换降低吞吐）。
     * min 2 保证低核机器也能并发。
     * daemon=true 保证 JVM 退出时不被阻塞。
     */
    private final ExecutorService ocrExecutor;

    public OcrService(OcrProperties props) {
        this.props = props;
        this.tesseractHolder = ThreadLocal.withInitial(this::createTesseract);
        this.ocrExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "ocr-worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        log.info("OcrService 初始化：language={}, tessdata={}, enabled={}",
                props.getLanguage(), props.getTessdataPath(), props.isEnabled());
    }

    /**
     * 创建 Tesseract 实例（每个线程调用一次）
     * <p>
     * 配置项从 OcrProperties 读取，避免硬编码。
     */
    private ITesseract createTesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(props.getTessdataPath());
        t.setLanguage(props.getLanguage());
        t.setPageSegMode(props.getPageSegMode());
        return t;
    }

    /**
     * 对图片执行 OCR
     *
     * @param image    图片对象（调用方负责解码/渲染）
     * @param sourceId 来源标识，用于日志定位（如 "合同.png" 或 "doc.pdf:p3"）
     * @return 识别后的文本；失败 / 未启用 / 文本过短时返回空字符串
     */
    public String ocr(BufferedImage image, String sourceId) {

        // ── 前置校验 ─────────────────────────────────────────────
        // 关闭开关 → 直接返回空，调用方无需感知
        if (!props.isEnabled()) {
            log.debug("OCR 未启用，跳过: {}", sourceId);
            return "";
        }
        if (image == null) {
            return "";
        }

        long start = System.currentTimeMillis();

        // ── 提交异步任务 ─────────────────────────────────────────
        // 用线程池而非当前线程执行，是为了让 Future.get 能控制超时
        Future<String> future = ocrExecutor.submit(() -> {
            ITesseract t = tesseractHolder.get();   // 拿本线程专属实例
            return t.doOCR(image);
        });

        try {
            // ── 等待结果（带超时） ──────────────────────────────
            String raw = future.get(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;

            // ── 清洗 + 长度过滤 ─────────────────────────────────
            String cleaned = cleanOcrText(raw);
            if (cleaned.length() < props.getMinTextLength()) {
                log.info("OCR 结果过短（{}字），视为无有效文本: {}",
                        cleaned.length(), sourceId);
                return "";
            }

            log.info("OCR 完成: {} | 耗时={}ms | 文本长度={}",
                    sourceId, cost, cleaned.length());
            return cleaned;

        } catch (TimeoutException e) {
            // 超时：中断任务，避免线程池被长期占用
            future.cancel(true);
            log.warn("OCR 超时（>{}s）: {}", props.getTimeoutSeconds(), sourceId);
            return "";

        } catch (Exception e) {
            // 其他异常（native 崩溃、语言包缺失等）：记录日志，返回空
            // ★ 不向上抛：一张图片失败不应中断整批入库
            log.error("OCR 失败: {}", sourceId, e);
            return "";
        }
    }

    /**
     * 清洗 OCR 原始输出
     * <p>
     * <b>为什么要清洗</b>：
     * Tesseract 对低质量图会产生三类噪声，都会污染 Embedding：
     * 1. 连续空白行 —— 来自段落间的空白区域
     * 2. 连续空格 —— 来自字间距过大的行
     * 3. 孤立符号 —— 来自边框、装饰线（如 "|||"、"~~~"）
     * <p>
     * <b>清洗策略</b>：保留正常换行（段落边界有价值），
     * 去除"结构噪声"，但不动正常字符。
     */
    private String cleanOcrText(String raw) {
        if (raw == null) return "";
        return raw
                // 去掉连续空白行：多个空行合并为一个
                // (?m) 让 ^ 和 $ 匹配每行开头/结尾
                .replaceAll("(?m)^[ \\t]*\\r?\\n", "\n")
                // 合并连续空格：把 2 个以上空格压成 1 个
                .replaceAll("[ \\t]{2,}", " ")
                // 去掉连续噪声符号：如 "|||"、"~~~"，单个保留
                .replaceAll("[|~^`]{2,}", "")
                .trim();
    }

    /**
     * 优雅关闭
     * <p>
     * Spring 容器销毁时调用，避免线程池泄漏。
     * shutdownNow 会尝试中断正在执行的 OCR（native 层可能忽略，
     * 但至少不再接受新任务）。
     */
    @PreDestroy
    public void shutdown() {
        ocrExecutor.shutdownNow();
        log.info("OcrService 线程池已关闭");
    }
}