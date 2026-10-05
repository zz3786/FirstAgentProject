package org.example.core.workflow.sequential.email.step;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.sequential.email.model.EmailWorkflowContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤 ②：总结
 * <p>
 * 输入：retrievedDocs
 * 输出：summary
 * 失败策略：LLM 调用失败 → 重试 1 次；仍失败则降级为"原文拼接"
 */
@Slf4j
@Component
public class EmailSummarizeStep {

    private final ChatClient chatClient;

    public EmailSummarizeStep(@Qualifier("plainChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(EmailWorkflowContext ctx) {
        long start = System.currentTimeMillis();

        try {
            String summary = summarizeWithRetry(ctx.getRetrievedDocs());
            ctx.setSummary(summary);
            ctx.recordTrace("总结", "SUCCESS",
                    System.currentTimeMillis() - start,
                    "要点 " + summary.length() + " 字");
            log.info("② 总结完成：{} 字", summary.length());

        } catch (Exception e) {
            // 降级：直接拼原文
            String fallback = buildFallback(ctx.getRetrievedDocs());
            ctx.setSummary(fallback);
            ctx.recordTrace("总结", "SKIPPED",
                    System.currentTimeMillis() - start,
                    "降级为原文拼接：" + e.getMessage());
            log.warn("② 总结失败，已降级为原文拼接", e);
        }
    }

    /** 带一次重试的总结 */
    private String summarizeWithRetry(List<Document> docs) {
        try {
            return doSummarize(docs);
        } catch (Exception first) {
            log.warn("总结首次失败，重试一次: {}", first.getMessage());
            return doSummarize(docs);
        }
    }

    private String doSummarize(List<Document> docs) {
        String docsText = buildDocsText(docs);
        String prompt = """
                请将以下资料提炼为 3~5 条核心要点，每条不超过 50 字。
                输出 Markdown 无序列表，不要额外解释。
                
                资料：
                %s
                """.formatted(docsText);

        String summary = chatClient.prompt().user(prompt).call().content();
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("模型返回空总结");
        }
        return summary;
    }

    /** 把文档拼成纯文本 */
    private String buildDocsText(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String source = (String) d.getMetadata().getOrDefault("source", "未知文档");
            sb.append("【").append(i + 1).append("】《").append(source).append("》\n");
            sb.append(d.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /** 降级：用原文前 500 字拼成粗糙总结 */
    private String buildFallback(List<Document> docs) {
        StringBuilder sb = new StringBuilder("[自动降级] 未成功提炼，原文摘录如下：\n\n");
        for (Document d : docs) {
            String text = d.getText();
            sb.append("- ").append(text.length() > 200
                    ? text.substring(0, 200) + "..."
                    : text).append("\n");
        }
        return sb.toString();
    }
}