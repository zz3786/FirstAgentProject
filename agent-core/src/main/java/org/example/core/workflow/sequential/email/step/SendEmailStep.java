package org.example.core.workflow.sequential.email.step;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.sequential.email.model.EmailDraft;
import org.example.core.workflow.sequential.email.model.EmailWorkflowContext;
import org.example.core.workflow.sequential.email.tool.EmailTools;
import org.springframework.stereotype.Component;

/**
 * 步骤 ④：发送邮件
 * <p>
 * 输入：draft
 * 输出：messageId
 * 失败策略：失败不抛出——降级为"返回草稿让用户手动发"
 */
@Slf4j
@Component
public class SendEmailStep {

    private final EmailTools emailTools;

    public SendEmailStep(EmailTools emailTools) {
        this.emailTools = emailTools;
    }

    public void execute(EmailWorkflowContext ctx) {
        long start = System.currentTimeMillis();
        EmailDraft draft = ctx.getDraft();

        try {
            String messageId = emailTools.send(
                    draft.to(), draft.subject(), draft.body());

            ctx.setMessageId(messageId);
            ctx.recordTrace("发送邮件", "SUCCESS",
                    System.currentTimeMillis() - start,
                    "messageId=" + messageId);
            log.info("④ 邮件发送成功：{}", messageId);

        } catch (Exception e) {
            // ★ 失败不抛——降级返回草稿
            ctx.recordTrace("发送邮件", "FAILED",
                    System.currentTimeMillis() - start,
                    "降级返回草稿：" + e.getMessage());
            log.warn("④ 邮件发送失败，已降级为返回草稿", e);
        }
    }
}