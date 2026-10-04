package org.example.rag.ingest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D55：文件摄取配置
 * <p>
 * 前缀：{@code app.rag.ingest.*}
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag.ingest")
public class IngestProperties {

    /** 原文件存储目录 */
    private String fileStorageDir = "./data/rag-files/";

    /** 监听文件目录 */
    private String listenFilesDir = "./data/testVectorData/";

    /** 扫描时间间隔（毫秒） */
    private Integer scanIntervalMs = 60000;

    /** 是否启用增量更新 */
    private Boolean incrementalEnabled = true;
}