package org.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D52 主动推荐配置
 * <p>
 * 与 RAG 检索参数分离——推荐是"锦上添花"，
 * 阈值更宽、topK 更大，和回答用的检索参数不同。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.recommendation")
public class RecommendationProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 最终返回几条推荐 */
    private int topK = 3;

    /**
     * 检索时召回条数（大于 topK，因为要过滤掉已回答引用的）
     * <p>
     * 建议：topK * 3，比如 topK=3 → recall=9
     */
    private int recallSize = 9;

    /** 取最近 N 条历史提问用于补足检索 */
    private int recentQueryCount = 2;

    /** 前端识别前缀 */
    private String prefix = "[RECOMMEND]";

    /** 每条推荐摘要的最大字符数 */
    private int maxSnippetChars = 80;
}