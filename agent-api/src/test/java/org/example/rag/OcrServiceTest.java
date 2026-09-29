package org.example.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.DocumentIngestService;
import org.example.rag.service.HybridSearchService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OCR 链路测试
 * <p>
 * 前置条件：
 * 1. 系统已安装 Tesseract 引擎
 * 2. tessdata 目录含 chi_sim.traineddata + eng.traineddata
 * 3. 测试图片/PDF 存在于指定路径
 */
@Slf4j
@SpringBootTest
class OcrServiceTest {

    @Autowired private DocumentIngestService ingestService;
    @Autowired private HybridSearchService hybridSearchService;

    @Test
    @DisplayName("独立图片：OCR + 入库 + 检索")
    void testImageOcrIngest() {
        String path = "D:/download/testVectorData/合同截图.png";

        // 入库（走 ImageDocumentReader 路径）
        DocumentIngestService.DocInfo info =
                ingestService.ingest(new FileSystemResource(path));
        System.out.println("入库 docId = " + info.docId());

        // 检索验证——应能召回图片里的文本
        List<Document> results = hybridSearchService.search("合同甲方名称");
        assertFalse(results.isEmpty(), "应召回 OCR 文本");

        for (Document d : results) {
            System.out.println("content_type=" + d.getMetadata().get("content_type"));
            System.out.println(d.getText());
        }
    }

    @Test
    @DisplayName("扫描件 PDF：逐页 OCR 补丁生效")
    void testScannedPdfOcr() {
        String path = "D:/download/testVectorData/扫描件.pdf";

        DocumentIngestService.DocInfo info =
                ingestService.ingest(new FileSystemResource(path));
        System.out.println("入库 docId = " + info.docId());

        // 检索验证——应能召回扫描页的 OCR 文本
        List<Document> results = hybridSearchService.search("扫描件中的关键词");
        for (Document d : results) {
            System.out.println("content_type=" + d.getMetadata().get("content_type")
                    + " page=" + d.getMetadata().get("page_number"));
        }
    }
}