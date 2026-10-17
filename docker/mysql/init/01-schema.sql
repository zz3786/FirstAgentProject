-- ============================================================
-- FirstAgentProject 数据库初始化（D78-B）
-- ------------------------------------------------------------
-- 执行时机：MySQL 容器首次启动（数据卷为空时）
-- 执行顺序：按文件名排序
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `rag`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `rag`;

CREATE TABLE IF NOT EXISTS `rag_chunks` (
                                            `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                                            `tenant_id`       VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `doc_id`          VARCHAR(64)  NOT NULL COMMENT '文档 ID',
    `chunk_index`     INT          NOT NULL DEFAULT 0 COMMENT '切片序号',
    `source`          VARCHAR(512) NULL COMMENT '原始文件名',
    `content`         LONGTEXT     NOT NULL COMMENT '切片内容',
    `file_path`       VARCHAR(512) NULL COMMENT '磁盘存储文件名',
    `department`      VARCHAR(64)  NULL COMMENT '部门',
    `year`            INT          NULL COMMENT '年份',
    `content_type`    VARCHAR(32)  NULL COMMENT 'text/table/image/pdf_ocr',
    `security_level`  INT          NOT NULL DEFAULT 1 COMMENT '1公开 2内部 3秘密 4机密',
    `status`          VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT 'active/archived/draft/deprecated',
    `page_number`     INT          NULL COMMENT '页码',
    `total_chunks`    INT          NULL COMMENT '总切片数',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_doc_id`        (`doc_id`),
    KEY `idx_tenant`        (`tenant_id`),
    KEY `idx_doc_chunk`     (`doc_id`, `chunk_index`),
    KEY `idx_filter`        (`tenant_id`, `department`, `status`, `security_level`),
    FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
                                              ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='RAG 切片表';

CREATE TABLE IF NOT EXISTS `rag_documents` (
                                               `id`             BIGINT        NOT NULL AUTO_INCREMENT,
                                               `file_path`      VARCHAR(1024) NOT NULL COMMENT '文件绝对路径（幂等 key）',
    `file_hash`      VARCHAR(128)  NOT NULL COMMENT 'SHA-256',
    `doc_id`         VARCHAR(64)   NOT NULL COMMENT '对应 rag_chunks.doc_id',
    `source`         VARCHAR(512)  NULL COMMENT '原文件名',
    `file_size`      BIGINT        NULL,
    `last_modified`  DATETIME      NULL,
    `indexed_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_path` (`file_path`(255)),
    KEY `idx_doc_id` (`doc_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='文档指纹表';

CREATE DATABASE IF NOT EXISTS `zipkin`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;