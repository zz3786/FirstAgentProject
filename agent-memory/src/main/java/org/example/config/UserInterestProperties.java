package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D53 用户兴趣标签配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.user-interest")
public class UserInterestProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 注入 Prompt 时最多展示几个标签 */
    private int injectTopN = 5;

    /** 每个用户最多保留的标签数（防膨胀） */
    private int maxTagsPerUser = 30;

    /** 标签 TTL（天）——长期不用自动衰减 */
    private long ttlDays = 180;

    /** 每次提及权重增加量 */
    private double weightIncrement = 0.15;

    /** 权重上限 */
    private double maxWeight = 1.0;

    /** 权重下限——低于此值在注入时过滤 */
    private double minWeight = 0.2;
}