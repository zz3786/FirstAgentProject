package org.example.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.workflow.sequential.email.SequentialEmailWorkflow;
import org.example.core.workflow.sequential.email.model.EmailWorkflowRequest;
import org.example.core.workflow.sequential.email.model.EmailWorkflowResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流入口
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class WorkflowController {

    private final SequentialEmailWorkflow emailWorkflow;

    public WorkflowController(SequentialEmailWorkflow emailWorkflow) {
        this.emailWorkflow = emailWorkflow;
    }

    /**
     * 触发"检索→总结→发邮件"工作流
     * <p>
     * 示例请求体：
     * <pre>
     * {
     *   "conversationId": "hospital-a:user-alice:sess-1",
     *   "fullUserId": "hospital-a:user-alice",
     *   "query": "家庭医生签约",
     *   "recipientEmail": "zhang@example.com",
     *   "recipientName": "张医生"
     * }
     * </pre>
     */
    @PostMapping("/email")
    public ApiResponse<EmailWorkflowResult> sendEmail(@RequestBody EmailRequest req) {
        EmailWorkflowRequest request = new EmailWorkflowRequest(
                req.conversationId(),
                req.fullUserId(),
                req.query(),
                req.recipientEmail(),
                req.recipientName()
        );
        return ApiResponse.ok(emailWorkflow.execute(request));
    }

    /** 请求体 */
    public record EmailRequest(
            String conversationId,
            String fullUserId,
            String query,
            String recipientEmail,
            String recipientName
    ) {}
}