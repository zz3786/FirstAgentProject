package org.example.multiagent.conflict.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.multiagent.model.AgentRole;

import java.util.List;

/**
 * 单个专家的立场（D76）
 *
 * <h3>职责</h3>
 * <p>
 * 由 {@code ConflictDetector} 从 Worker 的自由文本输出中提取——
 * 把长段落浓缩为"一句话立场 + 理由 + 关键论点"。
 *
 * <h3>为什么独立成模型而不复用 AgentResponse</h3>
 * <p>
 * {@code AgentResponse} 是"原始输出"——可能几百字。
 * {@code Opinion} 是"结构化立场"——用于聚类和投票。
 * 两者处于不同抽象层：原始 vs 提炼。
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code role}——来源角色</li>
 *   <li>{@code stance}——核心立场（一句话）。如"支持上线" / "反对上线"</li>
 *   <li>{@code reasoning}——支撑理由</li>
 *   <li>{@code keyPoints}——关键论点列表</li>
 *   <li>{@code confidence}——该立场的信心（0~1，-1 表示未评估）</li>
 * </ul>
 *
 * <h3>为什么没有"投票字段"</h3>
 * <p>
 * 投票由 {@code MajorityVoteResolver} 在"所有 opinions"层面做——
 * 不是每个 opinion 携带"投给谁"。保持 Opinion 纯粹。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Opinion(
        AgentRole role,
        String stance,
        String reasoning,
        List<String> keyPoints,
        double confidence
) {

    public Opinion {
        if (role == null) {
            role = AgentRole.TECHNICAL;
        }
        if (stance == null || stance.isBlank()) {
            stance = "未明确表态";
        }
        if (reasoning == null) {
            reasoning = "";
        }
        if (keyPoints == null) {
            keyPoints = List.of();
        }
    }

    /** 是否有效立场——"未明确表态"视为无效 */
    public boolean isMeaningful() {
        return !"未明确表态".equals(stance);
    }
}