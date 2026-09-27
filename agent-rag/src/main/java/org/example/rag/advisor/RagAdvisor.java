package org.example.rag.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.service.HybridSearchService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
public class RagAdvisor implements CallAdvisor, StreamAdvisor {

    private final HybridSearchService hybridSearchService;

    public RagAdvisor(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public String getName() {
        return "RagAdvisor";
    }

    @Override
    public int getOrder() {
        return 150;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(enrich(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(enrich(request));
    }

    private ChatClientRequest enrich(ChatClientRequest request) {
        // 1. 提取用户问题
        String query = request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse("");
        if (query.isBlank()) {
            return request;
        }

        // 2. 混合检索 + TEI 精排
        List<Document> docs = hybridSearchService.search(query);
        if (docs.isEmpty()) {
            log.info("RAG 无检索结果，跳过注入");
            return request;
        }

        // 3. 构造带编号的参考资料
        String context = buildContext(docs);

        // 4. 构造 Prompt（核心）
        String injection = buildRagPrompt(context);

        log.info("RAG 注入 {} 条资料", docs.size());

        Prompt newPrompt = request.prompt().augmentSystemMessage(injection);
        return request.mutate().prompt(newPrompt).build();
    }

    /**
     * 构造编号化的参考资料
     * <p>
     * 每条带【资料 N】编号，方便模型引用
     */
    private String buildContext(List<Document> docs) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);

            String source = (String) doc.getMetadata().getOrDefault("source", "未知文档");
            String docId = (String) doc.getMetadata().get("doc_id");
            Object page = doc.getMetadata().getOrDefault("page_number", "1");

            String link = (docId != null && !docId.isBlank())
                    ? "/fap/rag/file/download/" + docId
                    : "#";

            ctx.append(String.format("""
                    【资料 %d】
                    来源：《%s》第 %s 页
                    下载链接：%s
                    内容：%s
                    
                    """, i + 1, source, page, link, doc.getText()));
        }
        return ctx.toString();
    }

    /**
     * 构造 RAG Prompt —— Prompt 工程核心
     * <p>
     * 结构：角色 + 资料 + 规则 + 示例
     */
    private String buildRagPrompt(String context) {
        return """
                ========== 角色 ==========
                你是一个严谨的知识库助手。你的任务是严格基于【参考资料】回答用户问题。
                
                ========== 参考资料 ==========
                %s
                
                ========== 回答规则（必须严格遵守）==========
                
                【规则 1：只用资料】
                - 只使用【参考资料】中的信息回答
                - 禁止用你的训练知识补充、推测或编造
                - 资料中没有答案时，直接回答："根据现有资料，无法回答该问题。"
                
                【规则 2：引用标注】
                - 每条引用的信息后必须标注来源编号，格式：[资料 1]、[资料 2]
                - 一条信息来自多条资料时，标注：[资料 1][资料 3]
                
                【规则 3：回答格式】
                - 用简洁中文回答，不超过 300 字
                - 可用 markdown 列表、加粗提升可读性
                - 回答末尾必须用以下格式列出引用来源：
                
                  ---
                  **📄 引用来源：**
                  - [资料 N]：《文档名》第 X 页 [查看原文](下载链接)
                
                【规则 4：链接规范】
                - 链接必须原样复制资料中的「下载链接」，不要改写
                - 引用多条资料时，每条单独一行
                
                【规则 5：冲突与不完整】
                - 资料冲突时，同时列出并说明分歧
                - 资料不完整时，说明"资料仅提及 X，未展开说明 Y"
                
                ========== 正确回答示例 ==========
                
                用户问题：家庭医生签约后能享受什么服务？
                
                正确回答：
                家庭医生签约后可享受以下服务：
                - **基本公共卫生服务**：免费享受 12 大类 46 项服务 [资料 1]
                - **优先就诊**：签约社区服务中心优先就诊、优先预约 [资料 2]
                - **转诊绿色通道**：优先安排上级医院住院转诊 [资料 2]
                
                ---
                **📄 引用来源：**
                - [资料 1]：《家庭医生有偿签约服务协议书》第 1 页 [查看原文](/fap/rag/file/download/xxx)
                - [资料 2]：《家庭医生有偿签约服务协议书》第 2 页 [查看原文](/fap/rag/file/download/xxx)
                
                ========== 错误回答示例（禁止模仿）==========
                
                错误回答：家庭医生签约后可以享受免费体检、慢性病管理、健康咨询等服务。
                
                错误原因：没有引用编号，来源不明，可能来自模型编造。
                
                ========== 现在开始回答用户问题 ==========
                """.formatted(context);
    }
}