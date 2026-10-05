package org.example.workflow.model;

import java.util.List;

/**
 * 工作流最终返回——包含结果 + 全程轨迹
 */
public record WorkflowResult(
        boolean success,
        String messageId,          // 邮件 ID（发送成功才有）
        EmailDraft draft,          // 邮件草稿（发送失败也能返回，用户可手动发）
        List<StepTrace> traces,    // 每步轨迹
        long totalCostMs           // 总耗时
) {}