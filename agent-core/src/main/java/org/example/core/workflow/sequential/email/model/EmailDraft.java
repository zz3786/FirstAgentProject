package org.example.core.workflow.sequential.email.model;

/**
 * 邮件草稿——生成和发送分两步，中间可插入人工确认
 */
public record EmailDraft(
        String to,
        String subject,
        String body
) {}