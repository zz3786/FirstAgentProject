package org.example.core.workflow.sequential.email.model;

import lombok.Data;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流上下文——Step 之间共享的数据容器
 * <p>
 * 设计原则：只放"下游会用到"的数据；每步只留摘要+引用，原始数据丢弃。
 */
@Data
public class EmailWorkflowContext {

    // ==================== 输入（构造时定死） ====================
    private final EmailWorkflowRequest request;
    private final long startTime;

    // ==================== 中间结果（各 Step 逐步填充） ====================
    /** Step 1 输出：检索到的文档 */
    private List<Document> retrievedDocs;

    /** Step 2 输出：结构化总结 */
    private String summary;

    /** Step 3 输出：邮件草稿 */
    private EmailDraft draft;

    /** Step 4 输出：发送成功后的 messageId */
    private String messageId;

    // ==================== 执行轨迹 ====================
    private final List<EmailStepTrace> traces = new ArrayList<>();

    public EmailWorkflowContext(EmailWorkflowRequest request) {
        this.request = request;
        this.startTime = System.currentTimeMillis();
    }

    /** 记录一步的执行轨迹 */
    public void recordTrace(String stepName, String status, long costMs, String detail) {
        traces.add(new EmailStepTrace(stepName, status, costMs, detail));
    }

    /** 组装最终结果 */
    public EmailWorkflowResult toResult(boolean success) {
        return new EmailWorkflowResult(
                success,
                messageId,
                draft,
                traces,
                System.currentTimeMillis() - startTime
        );
    }
}