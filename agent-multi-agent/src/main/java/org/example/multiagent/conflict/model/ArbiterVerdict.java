package org.example.multiagent.conflict.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 仲裁者裁决（D76）
 *
 * <h3>LLM 契约</h3>
 * <p>
 * {@code ArbiterResolver} 用它生成 JSON schema 提示，并从 LLM 输出反序列化。
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code decision}——裁决结论。一句话明确表态</li>
 *   <li>{@code reasoning}——理由。为什么采信某方意见</li>
 *   <li>{@code winningRole}——采信的角色名（可为空，如"综合各方"）</li>
 *   <li>{@code confidence}——裁决信心 0~1。低信心时上层可以选择"说明不确定"</li>
 * </ul>
 *
 * <h3>为什么不用 boolean 表达"支持/反对"</h3>
 * <p>
 * 业务场景多样——不是所有冲突都是二选一。
 * 比如"方案 A 好还是方案 B 好"——裁决可能是"方案 A 适合小团队，方案 B 适合大团队"。
 * 用自由文本 decision 保留灵活性。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArbiterVerdict(
        @JsonPropertyDescription("明确的裁决结论。如果冲突是二选一的，直接说哪个；" +
                "如果是条件性的，说明条件")
        String decision,

        @JsonPropertyDescription("裁决理由。为什么采信某方或综合各方意见。150 字以内")
        String reasoning,

        @JsonPropertyDescription("采信的主要角色名（如 FINANCIAL / TECHNICAL / LEGAL）。" +
                "如果是综合各方，填 null 或空字符串")
        String winningRole,

        @JsonPropertyDescription("裁决信心 0.0~1.0。0.5 表示拿不准——那时应该" +
                "在裁决文本里明确指出不确定性")
        double confidence
) {

    public ArbiterVerdict {
        if (decision == null || decision.isBlank()) {
            decision = "无法裁决";
        }
        if (reasoning == null) {
            reasoning = "";
        }
        if (confidence < 0) {
            confidence = 0;
        }
        if (confidence > 1) {
            confidence = 1;
        }
    }

    /**
     * 兜底构造——LLM 输出异常时用
     */
    public static ArbiterVerdict safeDefault() {
        return new ArbiterVerdict(
                "无法做出裁决", "仲裁流程异常", null, 0.0);
    }
}