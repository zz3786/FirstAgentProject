package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片文件 OCR 读取器
 * <p>
 * <b>职责边界</b>：
 * - 只负责"独立图片文件（jpg/png/...） → List&lt;Document&gt;"
 * - 不做分块（返回单份 Document，交给 ingest 统一切片）
 * - 不填 doc_id / source / file_path（交给 injectMetadata 用 putIfAbsent 补）
 * <p>
 * <b>为什么只返回一份 Document</b>：
 * 图片 OCR 结果通常较短（几十到几百字），整份作为一个 Document 即可。
 * 若未来遇到"超长截图"，可以在 split() 阶段自然切开，无需在此处理。
 * <p>
 * <b>与 PDF OCR 补丁的区别</b>：
 * PDF 是"逐页判断是否需要 OCR"，图片文件是"整个文件就是 OCR 对象"。
 * 两者调用 OcrService 的方式相同，但上下文不同，因此独立成类。
 */
@Slf4j
@Service
public class ImageDocumentReader {

    private final OcrService ocrService;

    public ImageDocumentReader(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 读取图片文件 → OCR → Document
     *
     * @param resource Spring 的 Resource 抽象（FileSystemResource / ClassPathResource / ...）
     * @return 含 OCR 文本的 Document；无有效文本时返回空列表
     */
    public List<Document> read(Resource resource) {
        String name = resource.getFilename();

        try {
            // ── 解码图片 ────────────────────────────────────────
            // ImageIO.read 支持 jpg/png/bmp/gif（webp 需额外插件）
            // 返回 null 表示"无法识别格式"，而非异常
            BufferedImage image = ImageIO.read(resource.getInputStream());
            if (image == null) {
                log.warn("无法解码图片: {}", name);
                return List.of();
            }

            // ── OCR ────────────────────────────────────────────
            // sourceId 传文件名，方便日志里定位是哪张图
            String text = ocrService.ocr(image, name);
            if (text.isBlank()) {
                log.info("图片无有效文本: {}", name);
                return List.of();
            }

            // ── 打包 metadata ──────────────────────────────────
            // ★ 只填"图片专属"字段；doc_id/source/file_path 由 injectMetadata 补
            Map<String, Object> meta = new HashMap<>();
            meta.put("content_type", "image");        // 供检索时区分来源
            meta.put("image_width", image.getWidth());  // 供后续做布局分析
            meta.put("image_height", image.getHeight());

            return List.of(new Document(text, meta));

        } catch (Exception e) {
            // ★ 不向上抛：单张图片失败不应中断整批入库
            log.error("图片 OCR 失败: {}", name, e);
            return List.of();
        }
    }
}