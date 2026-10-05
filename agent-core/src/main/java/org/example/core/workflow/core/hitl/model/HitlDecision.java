package org.example.core.workflow.core.hitl.model;

import java.util.Map;

/**
 * 人工决策记录
 */
public record HitlDecision(
        HitlDecisionType type,
        String operatorId,                // 处理人工号
        String operatorName,              // 处理人姓名
        String comment,                   // 审批意见
        Map<String, Object> modifications, // 人工修改的参数（MODIFY 时使用）
        long decidedAt                    // 决策时间戳
) {
    public static HitlDecision approve(String operatorId, String operatorName, String comment) {
        return new HitlDecision(HitlDecisionType.APPROVE, operatorId, operatorName,
                comment, null, System.currentTimeMillis());
    }

    public static HitlDecision reject(String operatorId, String operatorName, String comment) {
        return new HitlDecision(HitlDecisionType.REJECT, operatorId, operatorName,
                comment, null, System.currentTimeMillis());
    }

    public static HitlDecision modify(String operatorId, String operatorName,
                                      String comment, Map<String, Object> mods) {
        return new HitlDecision(HitlDecisionType.MODIFY, operatorId, operatorName,
                comment, mods, System.currentTimeMillis());
    }
}