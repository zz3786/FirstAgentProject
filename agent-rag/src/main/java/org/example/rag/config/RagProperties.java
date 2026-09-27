package org.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 原文件存储目录 */
    private String fileStorageDir = "./data/rag-files/";

    /** 监听文件目录 */
    private String listenFilesDir = "./data/testVectorData/";

    /**
     * 扫描时间间隔
     */
    private Integer scanIntervalMs = 60000;

    /**
     * 是否启用增量更新
     */
    private Boolean incrementalEnabled = true;

    /** 检索 topK */
    private int topK = 5;

    /** 相似度阈值（<=0 表示不过滤） */
    private double similarityThreshold = 0.5;

    /** RRF 常数 */
    private int rrfK = 60;

    /** 每路召回放大倍数 */
    private int recallMultiplier = 2;

    /** ★ 新增：远程重排序配置 */
    private Rerank rerank = new Rerank();

    /**
     * 远程 Rerank 配置
     */
    @Data
    public static class Rerank {
        /** TEI 服务地址 */
        private String apiUrl = "http://localhost:8081/rerank";

        /** 请求超时（秒） */
        private int timeoutSeconds = 60;

        /** ★ 最低相关性分数（低于此值过滤掉） */
        private double minScore = 0.3;
    }



}