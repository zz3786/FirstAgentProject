package org.example.multiagent.aggregation.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.AggregationStrategy;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.model.AgentResponse;

/**
 * 简单拼接策略（D75）
 *
 * <h3>定位</h3>
 * <p>
 * 三个策略里最简单、最稳定、最"笨"的一个：
 * <ul>
 *   <li>不调 LLM</li>
 *   <li>不排序</li>
 *   <li>不计算权重</li>
 * </ul>
 * <p>
 * 它存在的意义是"<b>最后的兜底</b>"——当 LLM_SYNTHESIS 和
 * WEIGHTED_CONCAT 都失败时，至少能返回原始内容。
 *
 * <h3>何时主动用它</h3>
 * <ul>
 *   <li>调试——想看原始 Worker 输出</li>
 *   <li>极端保守的生产环境——宁可粗糙也不愿"被 LLM 改写"</li>
 * </ul>
 *
 * <h3>它的输出一定"难看"</h3>
 * <p>
 * 每段独立成块、不带过渡——这是有意为之。
 * 如果用户想要"连贯的答复"，用 LLM_SYNTHESIS；
 * 想要"结构化展示"，用 WEIGHTED_CONCAT。
 */
@Slf4j
public class PlainConcatStrategy implements AggregationStrategy {

    private static final int MAX_RESULT_CHARS = 2000;

    @Override
    public String name() {
        return "PLAIN_CONCAT";
    }

    @Override
    public AggregationResult aggregate(AggregationContext context) {
        long start = System.currentTimeMillis();

        if (!context.hasSuccess()) {
            long cost = System.currentTimeMillis() - start;
            return AggregationResult.fail("所有专家均未成功回答",
                    name(), cost, "无成功响应");
        }

        StringBuilder sb = new StringBuilder("专家原始意见：\n\n");
        for (AgentResponse resp : context.responses()) {
            sb.append("【").append(resp.role().getDisplayName()).append("】\n");
            if (resp.success()) {
                sb.append(truncate(resp.output(), MAX_RESULT_CHARS));
            } else {
                sb.append("（未成功：").append(resp.error()).append("）");
            }
            sb.append("\n\n");
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[PlainConcat] 完成: {} 条，耗时 {}ms", context.size(), cost);

        return AggregationResult.ok(sb.toString(), name(), cost);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}