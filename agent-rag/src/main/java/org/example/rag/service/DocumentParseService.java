package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档解析服务
 * <p>
 * 策略：
 * - PDF：用 PDFBox 按页解析（版式精准，支持页眉页脚裁剪）
 * - 其他格式（Word/PPT/HTML）：用 Tika 万能解析
 */
@Slf4j
@Service
public class DocumentParseService {

    /**
     * 解析 PDF，按页返回 Document
     * <p>
     * PagePdfDocumentReader 默认 pagesPerDocument=1（每页 = 一个 Document）[reference:5]。
     */
    public List<Document> parsePdf(Resource resource) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .withPageExtractedTextFormatter(
                        ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(1)
                                .withNumberOfBottomTextLinesToDelete(1)
                                .build()
                )
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> docs = reader.read();
        log.info("PDF 解析完成，页数: {}", docs.size());
        return docs;
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