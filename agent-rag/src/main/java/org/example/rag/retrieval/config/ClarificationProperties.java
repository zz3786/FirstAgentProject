package org.example.rag.retrieval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 澄清判定配置
 * <p>
 * <b>设计意图</b>：
 * 把"什么情况算模糊"这些阈值外置——让运维可以调，
 * 不用改代码重编译。
 * <p>
 * yml 覆盖示例：
 * <pre>
 * app:
 *   clarification:
 *     enabled: true
 *     min-top-score: 0.5
 *     min-qualified-docs: 2
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.clarification")
public class ClarificationProperties {

    /** 总开关——测试时可以关闭 */
    private boolean enabled = true;

    /**
     * 最高分阈值
     * <p>
     * TEI rerank 的最高分低于此值 → 判定为"完全不相关"
     * 经验值：0.5 是中文场景的常见分界
     */
    private double minTopScore = 0.5;

    /**
     * 至少需要几条高分文档
     * <p>
     * 只有 1 条高分 → 太单薄 → 追问
     * 2 条以上 → 有交叉印证 → 直接答
     */
    private int minQualifiedDocs = 2;

    /**
     * 反问文本前缀
     * <p>
     * 前端识别这个前缀 → 特殊渲染（黄色气泡、可点击选项等）
     */
    private String prefix = "[CLARIFY]";
}