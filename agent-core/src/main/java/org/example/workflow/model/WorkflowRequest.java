package org.example.workflow.model;

/**
 * 工作流入口请求
 */
public record WorkflowRequest(
        String conversationId,      // 会话 ID（用于日志追踪）
        String fullUserId,          // 完整用户 ID（tenantId:userId）
        String query,               // 检索关键词
        String recipientEmail,      // 收件人邮箱
        String recipientName        // 收件人姓名
) {}