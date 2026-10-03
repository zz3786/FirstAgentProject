package org.example.interest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * D53 用户兴趣标签
 * <p>
 * <b>为什么带权重</b>：
 * 用户提过一次"RAG"和提过十次"Spring AI"——关注强度不同。
 * 权重反映"提及频率 + 时间衰减"——注入 Prompt 时按权重排序取 TopN。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserInterestTag(
        String tag,           // 标签名，如 "Spring AI"
        double weight,        // 权重 [0, 1]
        long lastSeenAt,      // 最近提及时间戳（毫秒）
        int hitCount          // 累计提及次数
) {

    /** 初始标签——第一次提及时权重给一个基础值 */
    public static UserInterestTag initial(String tag) {
        return new UserInterestTag(tag, 0.3, System.currentTimeMillis(), 1);
    }

    /** 命中一次——权重上升、计数 +1 */
    public UserInterestTag hit(double increment, double maxWeight) {
        double newWeight = Math.min(maxWeight, this.weight + increment);
        return new UserInterestTag(tag, newWeight, System.currentTimeMillis(), hitCount + 1);
    }
}