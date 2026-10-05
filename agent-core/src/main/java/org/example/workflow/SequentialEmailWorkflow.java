package org.example.workflow;

import lombok.extern.slf4j.Slf4j;
import org.example.workflow.model.WorkflowContext;
import org.example.workflow.model.WorkflowRequest;
import org.example.workflow.model.WorkflowResult;
import org.example.workflow.step.ComposeEmailStep;
import org.example.workflow.step.RetrieveStep;
import org.example.workflow.step.SendEmailStep;
import org.example.workflow.step.SummarizeStep;
import org.springframework.stereotype.Service;

/**
 * 顺序工作流：检索 → 总结 → 生成邮件 → 发送邮件
 * <p>
 * 编排风格：命令式（每步一个方法/Service），流程写死在 execute 里。
 */
@Slf4j
@Service
public class SequentialEmailWorkflow {

    private final RetrieveStep retrieveStep;
    private final SummarizeStep summarizeStep;
    private final ComposeEmailStep composeEmailStep;
    private final SendEmailStep sendEmailStep;

    public SequentialEmailWorkflow(RetrieveStep retrieveStep,
                                   SummarizeStep summarizeStep,
                                   ComposeEmailStep composeEmailStep,
                                   SendEmailStep sendEmailStep) {
        this.retrieveStep = retrieveStep;
        this.summarizeStep = summarizeStep;
        this.composeEmailStep = composeEmailStep;
        this.sendEmailStep = sendEmailStep;
    }

    public WorkflowResult execute(WorkflowRequest request) {
        WorkflowContext ctx = new WorkflowContext(request);
        log.info("▶️ 工作流开始: query=[{}], recipient=[{}]",
                request.query(), request.recipientEmail());

        try {
            retrieveStep.execute(ctx);
            summarizeStep.execute(ctx);
            composeEmailStep.execute(ctx);
            sendEmailStep.execute(ctx);

            // 有 messageId 才算真正成功；没有的话是降级返回草稿
            boolean success = ctx.getMessageId() != null;
            log.info("✅ 工作流完成: success={}, messageId={}, 总耗时={}ms",
                    success, ctx.getMessageId(),
                    System.currentTimeMillis() - ctx.getStartTime());

            return ctx.toResult(success);

        } catch (Exception e) {
            log.error("❌ 工作流中止: {}", e.getMessage(), e);
            return ctx.toResult(false);
        }
    }
}