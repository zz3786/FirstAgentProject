package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析服务
 * <p>
 * 策略：
 * - Excel/CSV  → TableExtractService（已按行分块）
 * - PDF        → PagePdfDocumentReader（按页）
 * - 其他       → Tika 万能解析
 */
@Slf4j
@Service
public class DocumentParseService {

    private final TableExtractService tableExtractService;

    /** ★ 新增：图片读取器 */
    private final ImageDocumentReader imageDocumentReader;

    /** ★ 新增：OCR 服务（供扫描件 PDF 补丁使用） */
    private final OcrService ocrService;

    public DocumentParseService(TableExtractService tableExtractService, ImageDocumentReader imageDocumentReader, OcrService ocrService) {
        this.tableExtractService = tableExtractService;
        this.imageDocumentReader = imageDocumentReader;
        this.ocrService = ocrService;
    }

    public record ParseResult(List<Document> documents, boolean alreadyChunked) {}

    /**
     * 解析入口：按文件类型分派
     */
    /**
     * 解析入口：按文件类型分派
     * <p>
     * <b>分派优先级说明</b>：
     * 1. 表格（xlsx/xls/csv）—— 有专属解析器，产出已分块
     * 2. 图片（jpg/png/...）—— 走 OCR，产出待切片
     * 3. PDF —— 按页解析，含扫描件 OCR 补丁
     * 4. 其他 —— Tika 万能兜底
     * <p>
     * <b>为什么图片放在 PDF 之后判断</b>：
     * 顺序其实无关紧要——各后缀互不重叠。
     * 但语义上"表格 → 图片 → PDF → 其他"是"特殊 → 一般"的降序，
     * 便于阅读时快速扫到"特殊类型"。
     */
    public ParseResult parse(Resource resource) {
        String name = resource.getFilename();

        // ① 表格：已按行分块
        if (isTableFile(name)) {
            log.info("表格文件，走 TableExtractService: {}", name);
            return new ParseResult(tableExtractService.extract(resource), true);
        }

        // ② 图片：走 OCR（★ 新增）
        if (isImageFile(name)) {
            log.info("图片文件，走 OCR: {}", name);
            return new ParseResult(imageDocumentReader.read(resource), false);
        }

        // ③ PDF：按页解析 + 扫描件补丁
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new ParseResult(parsePdf(resource), false);
        }

        // ④ 其他：Tika 兜底
        return new ParseResult(parseWithTika(resource), false);
    }

    /**
     * ★ 新增：判断是否为图片文件
     * <p>
     * 覆盖常见格式；webp 需要额外依赖（webp-imageio）才能解码，
     * 若项目需要支持 webp，在 ImageDocumentReader 里加解码器即可。
     */
    private boolean isImageFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tiff")
                || lower.endsWith(".tif")
                || lower.endsWith(".webp");
    }

    private boolean isTableFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv");
    }

    /**
     * 解析 PDF，按页返回 Document
     * <p>
     * <b>两阶段策略</b>：
     * 1. 先用 PagePdfDocumentReader 提取文本层（快速，且保留版式）
     * 2. 对文本层为空的页（扫描件），渲染为图片走 OCR（慢，但能取字）
     * <p>
     * <b>为什么用"按页判断"而非"整份判断"</b>：
     * 很多 PDF 是混合的——前几页是文本，后几页是扫描件（如合同附件）。
     * 逐页判断能最大限度利用文本层的高质量内容，只在必要页走 OCR。
     * <p>
     * <b>裁剪页眉页脚</b>：
     * withNumberOfTopTextLinesToDelete(1) 等参数去掉页码、章节标题，
     * 避免这些噪声污染段落语义。
     */
    public List<Document> parsePdf(Resource resource) {

        // ── 配置 PDF 阅读器 ─────────────────────────────────────
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)    // 每页一个 Document（便于后续定位）
                .withPageExtractedTextFormatter(
                        ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(1)      // 裁 1 行页眉
                                .withNumberOfBottomTextLinesToDelete(1)   // 裁 1 行页脚
                                .build()
                )
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> docs = reader.read();

        // ── 逐页检查：空文本 → OCR 补丁 ────────────────────────
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);

            // 文本层有内容 → 直接采用（保留原生版式 metadata）
            if (doc.getText() != null && !doc.getText().isBlank()) {
                result.add(doc);
                continue;
            }

            // 文本层为空 → 判断为扫描件，走 OCR
            log.info("PDF 第 {} 页无文本层，尝试 OCR", i + 1);
            String ocrText = ocrPdfPage(resource, i);

            if (!ocrText.isBlank()) {
                // ★ 保留原 metadata（页号、页码等），追加 OCR 标记
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("content_type", "pdf_ocr");   // 供检索时区分来源
                meta.put("page_number", i + 1);        // 1-based，便于人类阅读
                result.add(new Document(ocrText, meta));
            }
            // 若 OCR 也取不到字（如纯色页/图形页），直接跳过此页
        }

        log.info("PDF 解析完成：{} 页（含 OCR）", result.size());
        return result;
    }

    /**
     * ★ 新增：把 PDF 指定页渲染为图片后 OCR
     * <p>
     * <b>DPI 选择</b>：
     * - 150 DPI：快速，适合纯文本扫描件
     * - 300 DPI：标准，平衡速度与精度（推荐）
     * - 600 DPI：高精度，但渲染+OCR 耗时会翻倍
     * <p>
     * 300 DPI 下 A4 页面约 2480×3508 像素，OCR 耗时 3-8 秒。
     * 若批处理场景对速度敏感，可降到 200 DPI 换取 2-3 倍提速。
     *
     * @param pageIndex 0-based 页码
     */
    private String ocrPdfPage(Resource resource, int pageIndex) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(resource.getFile())) {
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(document);

            // 渲染为图片
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300);

            // sourceId 含页码，方便日志定位
            return ocrService.ocr(image,
                    resource.getFilename() + ":p" + (pageIndex + 1));

        } catch (Exception e) {
            // ★ 单页失败不中断整份 PDF——其余页继续处理
            log.error("PDF 页面 OCR 失败: page={}", pageIndex + 1, e);
            return "";
        }
    }

    /**
     * 解析 Word / PPT / HTML 等，用 Tika
     */
    public List<Document> parseWithTika(Resource resource) {
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.read();
        log.info("Tika 解析完成，Document 数: {}", docs.size());
        return docs;
    }
}