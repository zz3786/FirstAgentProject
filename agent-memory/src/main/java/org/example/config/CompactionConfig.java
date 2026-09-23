package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话压缩配置
 * <p>
 * 控制 CompactingChatMemoryAdvisor 何时触发压缩、压缩后保留多少条消息。
 * 可通过 application.yml 覆盖：
 * <pre>
 * app:
 *   compaction:
 *     max-messages-before-compaction: 100
 *     keep-recent-messages: 20
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.compaction")
public class CompactionConfig {

    /** 触发压缩的阈值：CHAT 里消息数超过这个值就压缩 */
    private int maxMessagesBeforeCompaction = 100;

    /** 压缩后保留最近 N 条原始消息不压缩 */
    private int keepRecentMessages = 20;

    @PostConstruct
    public void print() {
        System.out.println(">>> CompactionConfig: max=" + maxMessagesBeforeCompaction
                + ", keep=" + keepRecentMessages);
    }
}