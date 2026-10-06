package org.example.core.workflow.sequential.email;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.sequential.email.model.EmailWorkflowContext;
import org.example.core.workflow.sequential.email.model.EmailWorkflowRequest;
import org.example.core.workflow.sequential.email.model.EmailWorkflowResult;
import org.example.core.workflow.sequential.email.step.ComposeEmailStep;
import org.example.core.workflow.sequential.email.step.EmailRetrieveStep;
import org.example.core.workflow.sequential.email.step.SendEmailStep;
import org.example.core.workflow.sequential.email.step.EmailSummarizeStep;
import org.springframework.stereotype.Service;

/**
 * 顺序工作流：检索 → 总结 → 生成邮件 → 发送邮件
 * <p>
 * 编排风格：命令式（每步一个方法/Service），流程写死在 execute 里。
 */
@Slf4j
@Service
public class SequentialEmailWorkflow {

    private final EmailRetrieveStep retrieveStep;
    private final EmailSummarizeStep summarizeStep;
    private final ComposeEmailStep composeEmailStep;
    private final SendEmailStep sendEmailStep;

    public SequentialEmailWorkflow(EmailRetrieveStep retrieveStep,
                                   EmailSummarizeStep summarizeStep,
                                   ComposeEmailStep composeEmailStep,
                                   SendEmailStep sendEmailStep) {
        this.retrieveStep = retrieveStep;
        this.summarizeStep = summarizeStep;
        this.composeEmailStep = composeEmailStep;
        this.sendEmailStep = sendEmailStep;
    }

    public EmailWorkflowResult execute(EmailWorkflowRequest request) {
        EmailWorkflowContext ctx = new EmailWorkflowContext(request);
        log.info("▶️ 工作流开始: query=[{}], recipient=[{}]",
                request.query(), request.recipientEmail());

        try {

            //检索
            retrieveStep.execute(ctx);

            //总结
            summarizeStep.execute(ctx);

            //生成邮件
            composeEmailStep.execute(ctx);

            //发送邮件
            sendEmailStep.execute(ctx);

            // 有 messageId 才算真正成功；没有的话是降级返回草稿
            boolean success = ctx.getMessageId() != null;
            log.info("✅ 工作流完成: success={}, messageId={}, 总耗时={}ms", success, ctx.getMessageId(),
                    System.currentTimeMillis() - ctx.getStartTime());

            return ctx.toResult(success);

        } catch (Exception e) {
            log.error("❌ 工作流中止: {}", e.getMessage(), e);
            return ctx.toResult(false);
        }
    }
}