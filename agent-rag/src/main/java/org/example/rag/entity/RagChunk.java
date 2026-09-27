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
}