package org.example.multiagent.conflict.model;

import java.util.Map;

/**
 * 冲突处理决议（D76）
 *
 * <h3>两种状态</h3>
 * <ol>
 *   <li><b>无冲突</b>——{@code hasConflict=false}，{@code resolution=null}</li>
 *   <li><b>有冲突已解决</b>——{@code hasConflict=true}，{@code resolution} 非空</li>
 * </ol>
 * <p>
 * 本类型<b>不表示"处理失败"</b>——处理失败时也返回"无冲突"（视为"没检测到需要处理的冲突"）。
 * 因为"没解决"和"没冲突"对下游是同样的语义——<b>按原样聚合</b>。
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code hasConflict}——是否曾检测到冲突</li>
 *   <li>{@code strategyName}——用了哪个 Resolver</li>
 *   <li>{@code resolution}——决议文本。会被聚合策略读到，用于"优先生成被裁决的结论"</li>
 *   <li>{@code winningRole}——胜出角色（投票时有；仲裁时可选）</li>
 *   <li>{@code severity}——冲突严重度（从 analysis 传递）</li>
 *   <li>{@code confidence}——决议的信心 0~1</li>
 *   <li>{@code costMs}——处理耗时</li>
 *   <li>{@code metadata}——扩展</li>
 * </ul>
 *
 * <h3>为什么 strategyName 不在 hasConflict=false 时省略</h3>
 * <p>
 * 即使无冲突，记录"谁检测的"（NONE / 规则预筛）对审计有价值。
 */
public record ConflictResolution(
        boolean hasConflict,
        String strategyName,
        String resolution,
        String winningRole,
        double severity,
        double confidence,
        long costMs,
        Map<String, Object> metadata
) {

    public ConflictResolution {
        if (strategyName == null) {
            strategyName = "NONE";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * 无冲突快捷构造——用于 Detector 判定无冲突的场景
     */
    public static ConflictResolution noConflict() {
        return new ConflictResolution(
                false, "NONE", null, null,
                0.0, 1.0, 0L, Map.of());
    }

    /**
     * 有冲突已解决
     */
    public static ConflictResolution resolved(String strategyName,
                                              String resolution,
                                              String winningRole,
                                              double severity,
                                              double confidence,
                                              long costMs) {
        return new ConflictResolution(
                true, strategyName, resolution, winningRole,
                severity, confidence, costMs, Map.of());
    }

    /**
     * 有冲突但未解决——两个 Resolver 都放弃时
     * <p>
     * 返回的 resolution 会建议聚合策略"明确说明存在分歧"。
     */
    public static ConflictResolution unresolved(String strategyName,
                                                String reason,
                                                double severity,
                                                long costMs) {
        return new ConflictResolution(
                true, strategyName,
                "存在未解决的分歧：" + reason,
                null, severity, 0.0, costMs,
                Map.of("unresolved", true, "reason", reason));
    }
}