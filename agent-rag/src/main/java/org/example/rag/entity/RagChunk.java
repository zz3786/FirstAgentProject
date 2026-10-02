package org.example.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG chunk 实体 —— 对应 rag_chunks 表
 */
@Data
public class RagChunk {

    private Long id;

    /** 文档 ID（对应 Qdrant 里的 doc_id） */
    private String docId;

    /** 在文档中的序号 */
    private Integer chunkIndex;

    /** 原始文件名 */
    private String source;

    /** 片段内容 */
    private String content;

    /** 磁盘存储文件名（{docId}.docx） */
    private String filePath;

    /** 关键词检索得分（非表字段，查询时填充） */
    private Double score;

    private LocalDateTime createdAt;

    // ★ D46 新增：过滤维度
    /** 部门 */
    private String department;

    /** 年份（整数，用于范围过滤） */
    private Integer year;

    /** 内容类型：text / table / image / pdf_ocr */
    private String contentType;

    // ★ D46 新增：权限与状态维度
    /** 密级：1=公开 2=内部 3=秘密 4=机密。查询时过滤 security_level <= 用户密级 */
    private Integer securityLevel;

    /** 文档状态：active / archived / draft / deprecated */
    private String status;

    // ★ 新增：页码和切片总数
    /** 页码：PDF 按页解析时为页号；其他类型为 null */
    private Integer pageNumber;

    /** 该文档的总 chunk 数 */
    private Integer totalChunks;
}