package org.example.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档指纹 —— 记录已入库文件的状态
 * <p>
 * 用于增量更新时对比文件变化
 */
@Data
public class DocumentFingerprint {

    private Long id;

    /** 文件绝对路径 */
    private String filePath;

    /** 文件 SHA-256 哈希 */
    private String fileHash;

    /** 对应 Qdrant/rag_chunks 的 docId */
    private String docId;

    /** 原文件名 */
    private String source;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件最后修改时间 */
    private LocalDateTime lastModified;

    /** 入库时间 */
    private LocalDateTime indexedAt;
}