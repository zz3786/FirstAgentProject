package org.example.multiagent.conflict.resolver;

import lombok.extern.slf4j.Slf4j;
import org.example.multiagent.conflict.ConflictResolver;
import org.example.multiagent.conflict.model.ConflictContext;
import org.example.multiagent.conflict.model.ConflictResolution;
import org.example.multiagent.conflict.model.Opinion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数决投票（D76）
 *
 * <h3>机制</h3>
 * <ol>
 *   <li>把所有有效立场按 stance 文本聚类</li>
 *   <li>找票数最多的 cluster</li>
 *   <li>如果该 cluster 票数 > 总票数的一半 → 该立场胜出</li>
 *   <li>否则 → 无多数意见——返回 unresolved</li>
 * </ol>
 *
 * <h3>聚类怎么实现</h3>
 * <p>
 * 当前用"文本规范化 + 完全匹配"：
 * <ul>
 *   <li>转小写、去空格、去标点</li>
 *   <li>取前 20 字作为 key</li>
 * </ul>
 * <p>
 * <b>这不是最优方案</b>——LLM 提取的立场可能有措辞差异。
 * 更优方案是用 embedding 相似度聚类，但那样需要额外一次 embedding 调用。
 * <p>
 * 当前方案的好处：
 * <ul>
 *   <li>零额外调用——省时间省成本</li>
 *   <li>可预测——同样输入总给同样输出</li>
 *   <li>足够用——LLM 提取时通常会有归一化倾向</li>
 * </ul>
 *
 * <h3>何时用</h3>
 * <ul>
 *   <li>有明确"选项"的场景——如"做/不做"、"方案A/方案B"</li>
 *   <li>不需要"权威判断"——多数即合理</li>
 * </ul>
 *
 * <h3>何时不用</h3>
 * <ul>
 *   <li>专家权重大不同——如法务意见在合规问题上更权威</li>
 *   <li>投票人数少（2 个）——平票概率高</li>
 * </ul>
 * <p>
 * 这些场景用 {@code ArbiterResolver}。
 */
@Slf4j
public class MajorityVoteResolver implements ConflictResolver {

    @Override
    public String name() {
        return "MAJORITY_VOTE";
    }

    @Override
    public ConflictResolution resolve(ConflictContext context) {
        long start = System.currentTimeMillis();

        List<Opinion> validOpinions = context.analysis().opinions().stream()
                .filter(Opinion::isMeaningful)
                .toList();

        if (validOpinions.size() < 2) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[MajorityVote] 有效立场 < 2——无法投票");
            return ConflictResolution.unresolved(
                    name(), "有效立场不足", context.analysis().severity(), cost);
        }

        // ① 聚类
        Map<String, List<Opinion>> clusters = new HashMap<>();
        for (Opinion op : validOpinions) {
            String key = normalizeStance(op.stance());
            clusters.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
        }

        // ② 找最大 cluster
        Map.Entry<String, List<Opinion>> winner = clusters.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .orElseThrow();

        int winnerCount = winner.getValue().size();
        int totalCount = validOpinions.size();
        long cost = System.currentTimeMillis() - start;

        // ③ 是否多数
        if (winnerCount * 2 > totalCount) {
            // 严格多数
            String winningRole = winner.getValue().get(0).role().name();
            String resolution = String.format(
                    "多数意见（%d/%d）来自 %s：%s",
                    winnerCount, totalCount,
                    winner.getValue().get(0).role().getDisplayName(),
                    winner.getValue().get(0).stance());

            log.info("[MajorityVote] 多数胜出: {} -> {}, 得票 {}/{}",
                    winner.getKey(), winningRole, winnerCount, totalCount);

            return ConflictResolution.resolved(
                    name(), resolution, winningRole,
                    context.analysis().severity(),
                    (double) winnerCount / totalCount,  // 信心 = 得票率
                    cost);
        }

        // ④ 无多数
        log.warn("[MajorityVote] 无多数意见: 最大 cluster {}/{}, 集群数 {}",
                winnerCount, totalCount, clusters.size());

        return ConflictResolution.unresolved(
                name(),
                String.format("无多数意见——最大意见组仅占 %d/%d",
                        winnerCount, totalCount),
                context.analysis().severity(),
                cost);
    }

    // ==================== 内部 ====================

    /**
     * 立场规范化——用于聚类 key
     * <p>
     * 目标：让"支持上线"、"支持上线！"、"支持 上线"归为一类。
     * <p>
     * 方案：转小写 + 去标点 + 去空白 + 取前 20 字。
     */
    private String normalizeStance(String stance) {
        if (stance == null) {
            return "";
        }
        String s = stance.toLowerCase()
                .replaceAll("[\\p{Punct}\\s，。！？；：]", "");
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}