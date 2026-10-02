package org.example.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.model.RagFilter;
import org.example.rag.service.DocumentIngestService;
import org.example.rag.service.HybridSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class MetadataFilterTest {

    @Autowired
    private HybridSearchService hybridSearchService;
    @Autowired private DocumentIngestService ingestService;

    @Test
    @DisplayName("入库时自动填充业务维度 metadata")
    void testMetadataInjection() {
        // 入库一个文件名含"财务部"和"2024"的文档
        var info = ingestService.ingest(
                new FileSystemResource("D:/testVectorData/财务部2024年报销制度.docx"));

        var results = hybridSearchService.search("报销标准", RagFilter.empty());
        assertFalse(results.isEmpty());
        Document first = results.get(0);
        assertEquals("财务部", first.getMetadata().get("department"));
        assertEquals(2024, ((Number) first.getMetadata().get("year")).intValue());
    }

    @Test
    @DisplayName("按部门过滤：只召回目标部门文档")
    void testFilterByDepartment() {
        var filter = new RagFilter(List.of("财务部"), null, null, null, null);
        var results = hybridSearchService.search("报销标准", filter);

        assertFalse(results.isEmpty(), "财务部应有相关文档");
        for (Document d : results) {
            assertEquals("财务部", d.getMetadata().get("department"),
                    "过滤后不应混入其他部门");
        }
    }

    @Test
    @DisplayName("按年份范围过滤：只召回指定区间")
    void testFilterByYearRange() {
        var filter = new RagFilter(null, 0, 2025, null, null);
        var results = hybridSearchService.search("制度", filter);

        for (Document d : results) {
            Object y = d.getMetadata().get("year");
            if (y != null) {
                int year = ((Number) y).intValue();
                assertTrue(year >= 2024 && year <= 2025,
                        "年份应在 [2024, 2025]，实际: " + year);
            }
        }
    }

    @Test
    @DisplayName("组合过滤：部门 + 年份 + 类型")
    void testCombinedFilter() {
        var filter = new RagFilter(
                List.of("财务部", "人事部"),
                2024, null,
                List.of("制度"), null);
        var results = hybridSearchService.search("管理规定", filter);

        for (Document d : results) {
            String dept = (String) d.getMetadata().get("department");
            if (dept != null) {
                assertTrue(dept.equals("财务部") || dept.equals("人事部"),
                        "部门应为财务部或人事部，实际: " + dept);
            }
        }
    }

    @Test
    @DisplayName("过滤后无结果：应返回空列表而非全部")
    void testFilterNoMatch() {
        var filter = new RagFilter(List.of("不存在的部门"), null, null, null, null);
        var results = hybridSearchService.search("报销标准", filter);

        // 不应因为过滤条件太窄就退化为"不过滤"
        assertTrue(results.isEmpty(),
                "不存在的部门不应召回任何结果，实际: " + results.size());
    }
}