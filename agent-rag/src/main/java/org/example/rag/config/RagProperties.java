package org.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 相关配置
 * <p>
 * 对应 application.yml 中的 app.rag.* 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 原文件存储目录（用于点击来源链接下载） */
    private String fileStorageDir = "./data/rag-files/";

    /** 待处理文件目录（批量入库时的扫描目录） */
    private String filesDir = "./data/testVectorData/";

    /** 检索 topK */
    private int topK = 1;

    /** 相似度阈值（<=0 表示不过滤） */
    private double similarityThreshold = 0.5;
}