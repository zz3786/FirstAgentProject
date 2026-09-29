package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 表格抽取服务
 * <p>
 * 目标：把 Excel / CSV 转成「保留列语义」的结构化文本。
 * <p>
 * 输出策略：
 * - 小表（≤ SMALL_TABLE_ROWS）：整表 Markdown
 * - 大表：按 CHUNK_ROWS 行分块，每块带表头 + KV 逐行描述
 * <p>
 * 与 TokenTextSplitter 的区别：
 * 表格自带行边界，按行切比按 token 切更合理——
 * 不会把一行从中间劈开，每块都能自解释。
 * <p>
 * <b>职责边界</b>：
 * 本类只填「表格专属 metadata」（sheet_name / header / row_start ...）。
 * 通用文档 metadata（doc_id / source / file_path）由调用方
 * {@code DocumentIngestService.injectMetadata()} 用 putIfAbsent 补齐——
 * 两条路径（文本 / 表格）共用同一个注入入口，主干不分支。
 */
@Slf4j
@Service
public class TableExtractService {

    /** 小表阈值：不超过这个行数就整表输出 Markdown */
    private static final int SMALL_TABLE_ROWS = 30;

    /** 大表分块行数 */
    private static final int CHUNK_ROWS = 20;

    /** 超过这个列数就截断（防止极端宽表撑爆 token） */
    private static final int MAX_COLS = 30;

    // ==================== 入口 ====================

    /**
     * 表格抽取入口：按扩展名分派
     * <p>
     * 返回的 Document 已按行分块完毕，调用方不应再走 TokenTextSplitter。
     */
    public List<Document> extract(Resource resource) {
        String name = resource.getFilename();
        if (name == null) {
            return List.of();
        }
        return name.toLowerCase().endsWith(".csv")
                ? parseCsv(resource)
                : parseExcel(resource);
    }

    // ==================== Excel ====================

    /**
     * 解析 Excel（xls / xlsx），按 sheet 逐个提取
     */
    public List<Document> parseExcel(Resource resource) {
        List<Document> result = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(resource.getInputStream())) {
            DataFormatter fmt = new DataFormatter();

            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                List<List<String>> rows = readSheet(sheet, fmt);
                if (rows.isEmpty()) {
                    log.info("Sheet [{}] 为空，跳过", sheetName);
                    continue;
                }

                result.addAll(buildDocuments(rows, "excel", sheetName));
            }

            log.info("Excel 解析完成：{} 个 sheet → {} 个 chunk",
                    wb.getNumberOfSheets(), result.size());

        } catch (Exception e) {
            log.error("解析 Excel 失败: {}", resource.getFilename(), e);
        }
        return result;
    }

    /** 读取单个 sheet 为二维字符串表 */
    private List<List<String>> readSheet(Sheet sheet, DataFormatter fmt) {
        List<List<String>> rows = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();

        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            int lastCell = Math.min(row.getLastCellNum(), MAX_COLS);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                // ★ DataFormatter：日期/数字自动按显示格式转字符串
                //   比 getStringCellValue 安全——后者遇到数值列会抛异常
                cells.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
            }
            if (!isBlankRow(cells)) {
                rows.add(cells);
            }
        }
        return rows;
    }

    // ==================== CSV ====================

    public List<Document> parseCsv(Resource resource) {
        try (Reader reader = new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < Math.min(rec.size(), MAX_COLS); i++) {
                    cells.add(rec.get(i));
                }
                if (!isBlankRow(cells)) {
                    rows.add(cells);
                }
            }

            List<Document> docs = buildDocuments(rows, "csv", "CSV");

            log.info("CSV 解析完成：{} 行 → {} 个 chunk", rows.size(), docs.size());
            return docs;

        } catch (Exception e) {
            log.error("解析 CSV 失败: {}", resource.getFilename(), e);
            return List.of();
        }
    }

    // ==================== 核心：二维表 → Document ====================

    /**
     * @param rows       完整二维表（含表头行）
     * @param tableType  "excel" / "csv"
     * @param sheetName  sheet 名 / CSV 固定 "CSV"
     */
    private List<Document> buildDocuments(List<List<String>> rows,
                                          String tableType,
                                          String sheetName) {
        if (rows.size() < 2) {
            log.warn("[{}] 行数不足 2（只有表头或空），跳过", sheetName);
            return List.of();
        }

        // ① 表头 + 数据行
        List<String> header = normalizeHeader(rows.get(0));
        List<List<String>> dataRows = rows.subList(1, rows.size());

        // ② 补齐列数（防止某些行短于表头，导致 KV 错位）
        //    ★ 用 new ArrayList<>(row) 包一层，避免直接改 subList 视图
        int colCount = header.size();
        List<List<String>> normalized = new ArrayList<>(dataRows.size());
        for (List<String> row : dataRows) {
            List<String> copy = new ArrayList<>(row);
            while (copy.size() < colCount) {
                copy.add("");
            }
            normalized.add(copy);
        }

        int totalRows = normalized.size();

        // ③ 小表：整表一份 Document
        if (totalRows <= SMALL_TABLE_ROWS) {
            String text = renderMarkdown(sheetName, header, normalized);
            return List.of(buildDoc(text, tableType, sheetName, header,
                    1, totalRows, totalRows, 0, 1, true));
        }

        // ④ 大表：按行分块，每块带表头
        List<Document> chunks = new ArrayList<>();
        int chunkIdx = 0;
        int totalChunks = (int) Math.ceil((double) totalRows / CHUNK_ROWS);

        for (int start = 0; start < totalRows; start += CHUNK_ROWS) {
            int end = Math.min(start + CHUNK_ROWS, totalRows);
            List<List<String>> block = normalized.subList(start, end);

            String text = renderKvBlock(sheetName, header,
                    block, start + 1, end, totalRows);

            chunks.add(buildDoc(text, tableType, sheetName, header,
                    start + 1, end, totalRows,
                    chunkIdx++, totalChunks, false));
        }
        return chunks;
    }

    // ==================== 渲染 ====================

    /** 小表：Markdown */
    private String renderMarkdown(String sheetName,
                                  List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【表格 | Sheet: ").append(sheetName)
                .append(" | 共 ").append(rows.size()).append(" 行 ")
                .append(header.size()).append(" 列】\n\n");

        // 表头行
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        // 分隔行
        sb.append("|");
        header.forEach(h -> sb.append(" --- |"));
        sb.append("\n");
        // 数据行
        for (List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 大表分块：KV 逐行描述，每块重复表头
     * <p>
     * 为什么用 KV 而不是 Markdown？
     * - embedding 抓"字段名=值"的共现更强
     * - 不依赖列对齐，行长度不齐也不影响语义
     */
    private String renderKvBlock(String sheetName,
                                 List<String> header, List<List<String>> block,
                                 int rowStart, int rowEnd, int totalRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【表格 | Sheet: ").append(sheetName)
                .append(" | 第 ").append(rowStart).append("-").append(rowEnd)
                .append(" 行 / 共 ").append(totalRows).append(" 行】\n");
        sb.append("表头：").append(String.join(" | ", header)).append("\n");
        sb.append("数据：\n");

        for (int i = 0; i < block.size(); i++) {
            List<String> row = block.get(i);
            int globalRowNum = rowStart + i;
            sb.append("- [行").append(globalRowNum).append("] ");
            List<String> kv = new ArrayList<>();
            for (int c = 0; c < header.size() && c < row.size(); c++) {
                String v = row.get(c);
                if (v == null || v.isBlank()) {
                    continue;
                }
                kv.add(header.get(c) + "=" + v);
            }
            sb.append(String.join("; ", kv)).append("\n");
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================

    /**
     * 构造 Document —— 只填「表格专属 metadata」
     * <p>
     * doc_id / source / file_path 由调用方 injectMetadata() 补齐，
     * chunk_index / total_chunks 若此处已填，对方 putIfAbsent 时不会覆盖。
     */
    private Document buildDoc(String text, String tableType, String sheetName,
                              List<String> header, int rowStart, int rowEnd,
                              int totalRows, int chunkIndex, int totalChunks,
                              boolean wholeTable) {
        Map<String, Object> meta = new HashMap<>();
        // ★ 表格自己填的分块信息（injectMetadata 用 putIfAbsent 会尊重它）
        meta.put("chunk_index", chunkIndex);
        meta.put("total_chunks", totalChunks);
        // 表格专属字段
        meta.put("content_type", "table");
        meta.put("table_type", tableType);
        meta.put("sheet_name", sheetName);
        meta.put("header", String.join("|", header));
        meta.put("row_start", rowStart);
        meta.put("row_end", rowEnd);
        meta.put("total_rows", totalRows);
        meta.put("whole_table", wholeTable);
        // ★ 不填 doc_id / source / file_path —— 交给 injectMetadata
        return new Document(text, meta);
    }

    private List<String> normalizeHeader(List<String> rawHeader) {
        List<String> header = new ArrayList<>();
        for (int i = 0; i < rawHeader.size(); i++) {
            String h = rawHeader.get(i);
            // 空表头补一个占位名，避免 "=值" 这种无字段名的 KV
            header.add((h == null || h.isBlank()) ? "列" + (i + 1) : h.trim());
        }
        return header;
    }

    private boolean isBlankRow(List<String> cells) {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }
}