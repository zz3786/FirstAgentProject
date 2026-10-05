package org.example.core.workflow.sequential.email.step;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.retrieval.service.HybridSearchService;
import org.example.rag.shared.model.RagFilter;
import org.example.core.workflow.sequential.email.exception.WorkflowException;
import org.example.core.workflow.sequential.email.model.EmailWorkflowContext;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤 ①：检索
 * <p>
 * 输入：query
 * 输出：retrievedDocs
 * 失败策略：检索为空直接中止（没资料没得聊）
 */
@Slf4j
@Component
public class EmailRetrieveStep {

    private final HybridSearchService hybridSearchService;

    public EmailRetrieveStep(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    public void execute(EmailWorkflowContext ctx) {
        long start = System.currentTimeMillis();
        String query = ctx.getRequest().query();

        try {
            List<Document> docs = hybridSearchService.search(query, RagFilter.empty());

            if (docs == null || docs.isEmpty()) {
                throw new IllegalStateException("知识库未找到与「" + query + "」相关的资料");
            }

            ctx.setRetrievedDocs(docs);
            ctx.recordTrace("检索", "SUCCESS",
                    System.currentTimeMillis() - start,
                    "召回 " + docs.size() + " 条");
            log.info("① 检索完成：{} 条", docs.size());

        } catch (Exception e) {
            ctx.recordTrace("检索", "FAILED",
                    System.currentTimeMillis() - start, e.getMessage());
            throw new WorkflowException("检索", "检索失败", e);
        }
    }
}