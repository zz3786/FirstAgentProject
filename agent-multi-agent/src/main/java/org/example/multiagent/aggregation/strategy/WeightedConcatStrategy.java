package org.example.multiagent.aggregation.strategy;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.aggregation.AggregationStrategy;
import org.example.multiagent.aggregation.model.AggregationContext;
import org.example.multiagent.aggregation.model.AggregationResult;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 加权拼接策略（D75）
 *
 * <h3>与 LLM_SYNTHESIS 的区别</h3>
 * <ul>
 *   <li><b>不调 LLM</b>——纯 Java 排序 + 拼接，<b>确定性</b>输出</li>
 *   <li><b>保留专家视角</b>——每个 Worker 的结果单独成段，不融合</li>
 *   <li><b>按权重排序</b>——重要的视角排前面</li>
 * </ul>
 *
 * <h3>权重怎么算</h3>
 * <pre>
 *   weight = roleWeight × qualityFactor
 *
 *   roleWeight：角色基础权重（yml 可配）
 *     FINANCIAL = 1.0
 *     TECHNICAL = 1.0
 *     LEGAL     = 1.0
 *
 *   qualityFactor：质量调节因子
 *     无质量分（-1）  → 1.0（中性——不惩罚）
 *     有质量分（0~1） → 0.5 + score × 0.5  （映射到 [0.5, 1.0]）
 * </pre>
 * <p>
 * 例：FINANCIAL 且 qualityScore=0.9 → weight = 1.0 × (0.5 + 0.45) = 0.95
 *
 * <h3>为什么 qualityFactor 映射到 [0.5, 1.0] 而非 [0, 1]</h3>
 * <p>
 * 质量分 0 的场景极罕见（说明回答完全无用）。映射到 [0.5, 1.0] 保证：
 * <ul>
 *   <li>即使质量低，也不至于被排到最后——毕竟它有内容</li>
 *   <li>质量分差异体现在"排序"上，不是"筛除"上</li>
 * </ul>
 *
 * <h3>何时使用</h3>
 * <ul>
 *   <li>LLM 服务不稳定——避免"聚合也依赖 LLM"的连锁故障</li>
 *   <li>需要"可审计的确定性输出"——每段来自哪个 Worker 一目了然</li>
 *   <li>前端要"卡片式展示"——每段独立渲染</li>
 * </ul>
 *
 * <h3>与 D76 的关系</h3>
 * <p>
 * D76 的冲突处理会读这个策略的 {@code metadata["opinions"]}——
 * 加权后的观点列表是"分歧地图"的输入。
 */
@Slf4j
public class WeightedConcatStrategy implements AggregationStrategy {

    /** 单 Worker 结果最大字符数 */
    private static final int MAX_RESULT_CHARS = 2000;

    /** 质量分缺失时的中性因子 */
    private static final double NEUTRAL_QUALITY_FACTOR = 1.0;

    private final Map<AgentRole, Double> roleWeights;

    public WeightedConcatStrategy(Map<AgentRole, Double> roleWeights) {
        this.roleWeights = roleWeights == null ? Map.of() : roleWeights;
    }

    @Override
    public String name() {
        return "WEIGHTED_CONCAT";
    }

    @Override
    public AggregationResult aggregate(AggregationContext context) {
        long start = System.currentTimeMillis();

        if (!context.hasSuccess()) {
            long cost = System.currentTimeMillis() - start;
            return AggregationResult.fail("所有专家均未成功回答",
                    name(), cost, "无成功响应");
        }

        // ① 计算加权后的观点列表
        List<WeightedOpinion> weighted = new ArrayList<>();
        for (AgentResponse resp : context.responses()) {
            double weight = computeWeight(resp);
            weighted.add(new WeightedOpinion(resp, weight));
        }

        // ② 按权重降序排序
        weighted.sort(Comparator.comparingDouble(WeightedOpinion::weight).reversed());

        // ③ 拼接
        StringBuilder sb = new StringBuilder();
        sb.append("综合专家意见（按权重排序）：\n\n");

        for (int i = 0; i < weighted.size(); i++) {
            WeightedOpinion wo = weighted.get(i);
            AgentResponse resp = wo.response;

            sb.append("【").append(i + 1).append("：")
                    .append(resp.role().getDisplayName())
                    .append("】");

            if (resp.qualityScore() > 0) {
                sb.append("（质量 ")
                        .append(String.format("%.0f%%", resp.qualityScore() * 100))
                        .append("）");
            }
            sb.append("\n");

            if (resp.success()) {
                sb.append(truncate(resp.output(), MAX_RESULT_CHARS));
            } else {
                sb.append("（该视角未成功：").append(resp.error()).append("）");
            }
            sb.append("\n\n");
        }

        long cost = System.currentTimeMillis() - start;

        log.info("[WeightedConcat] 完成: {} 条观点，总耗时 {}ms",
                weighted.size(), cost);

        // ④ 组装 metadata——D76 会读取 opinions 做冲突分析
        Map<String, Object> meta = Map.of(
                "inputCount", context.size(),
                "successCount", context.successCount(),
                "opinions", weighted.stream()
                        .map(wo -> Map.of(
                                "role", wo.response.role().name(),
                                "weight", wo.weight,
                                "success", wo.response.success()))
                        .toList()
        );

        return AggregationResult.ok(sb.toString(), name(), cost, meta);
    }

    // ==================== 内部 ====================

    private double computeWeight(AgentResponse resp) {
        // role 基础权重——不在配置里就默认 1.0
        double roleWeight = roleWeights.getOrDefault(resp.role(), 1.0);

        // 质量因子——无质量分时用中性值
        double qualityFactor = NEUTRAL_QUALITY_FACTOR;
        if (resp.qualityScore() >= 0) {
            // 映射到 [0.5, 1.0]
            qualityFactor = 0.5 + resp.qualityScore() * 0.5;
        }

        return roleWeight * qualityFactor;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 内部：加权观点 */
    private record WeightedOpinion(AgentResponse response, double weight) {}
}