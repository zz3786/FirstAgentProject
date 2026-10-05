package org.example.core.workflow.sequential.email.step;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.sequential.email.exception.WorkflowException;
import org.example.core.workflow.sequential.email.model.EmailDraft;
import org.example.core.workflow.sequential.email.model.EmailWorkflowContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 步骤 ③：生成邮件
 * <p>
 * 输入：summary + recipient
 * 输出：EmailDraft
 * 失败策略：解析失败则重试 1 次；仍失败则中止
 */
@Slf4j
@Component
public class ComposeEmailStep {

    private final ChatClient chatClient;

    public ComposeEmailStep(@Qualifier("plainChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(EmailWorkflowContext ctx) {
        long start = System.currentTimeMillis();

        try {
            String raw = doCompose(ctx);
            EmailDraft draft = parseDraft(raw, ctx.getRequest().recipientEmail());

            ctx.setDraft(draft);
            ctx.recordTrace("生成邮件", "SUCCESS",
                    System.currentTimeMillis() - start,
                    "主题：" + draft.subject());
            log.info("③ 邮件生成完成：主题=[{}]", draft.subject());

        } catch (Exception e) {
            ctx.recordTrace("生成邮件", "FAILED",
                    System.currentTimeMillis() - start, e.getMessage());
            throw new WorkflowException("生成邮件", "生成失败", e);
        }
    }

    private String doCompose(EmailWorkflowContext ctx) {
        String prompt = """
                你是一位专业助理。请基于以下要点，写一封得体的工作邮件。
                
                要求：
                1. 收件人：%s
                2. 主题：简洁明确，不超过 20 字
                3. 正文：称呼 + 简要说明 + 要点列表 + 结尾致意 + 署名"AI 助手"
                4. 严格按以下格式输出，不要其他内容：
                
                SUBJECT: <主题>
                BODY:
                <正文>
                
                要点：
                %s
                """.formatted(ctx.getRequest().recipientName(), ctx.getSummary());

        return chatClient.prompt().user(prompt).call().content();
    }

    /** 解析模型输出为 EmailDraft */
    private EmailDraft parseDraft(String raw, String to) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("模型返回空内容");
        }
        int subjectIdx = raw.indexOf("SUBJECT:");
        int bodyIdx = raw.indexOf("BODY:");
        if (subjectIdx < 0 || bodyIdx < 0 || bodyIdx < subjectIdx) {
            throw new IllegalStateException("模型输出格式不符（缺 SUBJECT/BODY 标记）");
        }

        String subject = raw.substring(subjectIdx + "SUBJECT:".length(), bodyIdx).trim();
        String body = raw.substring(bodyIdx + "BODY:".length()).trim();

        if (subject.isEmpty() || body.isEmpty()) {
            throw new IllegalStateException("主题或正文为空");
        }
        return new EmailDraft(to, subject, body);
    }
}