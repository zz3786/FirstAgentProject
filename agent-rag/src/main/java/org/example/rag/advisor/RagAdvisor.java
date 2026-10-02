package org.example.rag.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.model.RagFilter;
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

/**
 * RAG 检索增强 Advisor
 * <p>
 * <b>职责（两件事）</b>：
 * <ol>
 *   <li><b>获取 docs</b>——优先用调用方通过 {@code prefetched_docs} 传入的前置结果；
 *       没有时回退到"现场检索"（兼容绕过 ChatService 直接调 ChatClient 的场景）</li>
 *   <li><b>注入 SystemMessage</b>——把 docs 包装成带编号、带下载链接的 Prompt 片段，
 *       追加到 SystemMessage。这是 ChatService 无法代替的职责——
 *       ChatClient 的 prompt 构造只在 Advisor 链内完成</li>
 * </ol>
 *
 * <h3>为什么会有"前置检索"</h3>
 * D47 澄清判定要求在"调 LLM 之前"拿到检索结果——
 * Advisor 无法短路、工具无法短路，只有 ChatService（调用层）能。
 * 所以检索被提前到 ChatService 做一次，结果通过 {@code prefetched_docs} 传下来，
 * 本 Advisor 直接复用——避免重复 embedding / 重复 Qdrant 往返。
 *
 * <h3>执行顺序</h3>
 * order = 150——在 PreferenceAdvisor(100) 之后、MemoryRetrievalAdvisor(200) 之前。
 */
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
        log.info("[ENTER] {} order={}", getName(), getOrder());
        return chain.nextStream(enrich(request))
                .doOnComplete(() -> log.info("[EXIT]  {} order={}", getName(), getOrder()));
    }

    /**
     * 从上下文中取 docs 并注入 SystemMessage
     * <p>
     * <b>docs 的来源（两种）</b>：
     * <ol>
     *   <li><b>前置检索</b>——ChatService 通过
     *       {@code .advisors(a -> a.param("prefetched_docs", docs))} 传入。
     *       D47 主路径，一次检索两用（澄清判定 + 注入），避免重复算 embedding</li>
     *   <li><b>现场检索</b>——context 里没有 {@code prefetched_docs} 时回退。
     *       兼容绕过 ChatService 直接调 ChatClient 的场景（测试类、未来可能的接口）</li>
     * </ol>
     *
     * <b>关键：为什么判断 {@code instanceof List} 而不判断 {@code !isEmpty()}</b>：
     * 前置检索返回空列表是"有效信息"——表示"确实没结果"。
     * 如果因为空就回退现场检索——会重复检索一次，白花 embedding 费用。
     * 所以只要 context 里存在 {@code prefetched_docs}（哪怕是空 List）——就用它。
     */
    private ChatClientRequest enrich(ChatClientRequest request) {
        // ① 提取用户问题（Prompt 里最后一条 USER 消息）
        String query = request.prompt().getInstructions().stream()
                .filter(m -> "USER".equals(m.getMessageType().name()))
                .map(Message::getText)
                .reduce((a, b) -> b)
                .orElse("");
        if (query.isBlank()) {
            return request;
        }

        // ② 从 context 取过滤条件（前端可传 department / year，服务端强制 security_level / status）
        RagFilter filter = (RagFilter) request.context().get("rag_filter");

        // ③ ★ 获取 docs——优先前置，兜底现场
        List<Document> docs;
        Object prefetched = request.context().get("prefetched_docs");
        if (prefetched instanceof List<?> list) {
            // ★ 前置检索结果——ChatService 已完成检索，直接用
            //   注意：不判断 isEmpty——空 List 是"确实没结果"的有效信号
            docs = (List<Document>) list;
            log.info("RAG 使用前置检索结果，共 {} 条（过滤={}）", docs.size(), filter);
        } else {
            // ★ 无前置结果（绕过 ChatService 直接调 ChatClient）——回退现场检索
            docs = hybridSearchService.search(query, filter);
            log.info("RAG 现场检索，返回 {} 条（过滤={}）", docs.size(), filter);
        }

        // ④ 无结果 → 跳过注入（不污染 Prompt）
        if (docs.isEmpty()) {
            log.info("RAG 无检索结果（过滤={}），跳过注入", filter);
            return request;
        }

        // ⑤ 注入 SystemMessage——这部分逻辑不变
        String context = buildContext(docs);
        String injection = buildRagPrompt(context);
        log.info("RAG 注入 {} 条资料（过滤={}）", docs.size(), filter);

        Prompt newPrompt = request.prompt().augmentSystemMessage(injection);
        return request.mutate().prompt(newPrompt).build();
    }

    // ==================== 以下方法完全不变 ====================

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

            // ★ 取页码——取不到显示 "?"，不再谎报第 1 页
            Object page = doc.getMetadata().get("page_number");
            if (page == null) page = doc.getMetadata().get("pageNumber");
            String pageText = (page instanceof Number n) ? String.valueOf(n.intValue()) : "?";

            // ★ 顺带把切片信息带上（可选）——让模型知道这段在原文档的位置
            Object totalChunks = doc.getMetadata().get("total_chunks");
            String chunkInfo = "";
            Object chunkIdx = doc.getMetadata().get("chunk_index");
            if (chunkIdx instanceof Number ci && totalChunks instanceof Number tc) {
                chunkInfo = String.format("（第 %d/%d 段）", ci.intValue() + 1, tc.intValue());
            }

            String link = (docId != null && !docId.isBlank())
                    ? "/fap/rag/file/download/" + docId
                    : "#";

            ctx.append(String.format("""
                    【资料 %d】
                    来源：《%s》第 %s 页%s
                    下载链接：%s
                    内容：%s

                    """, i + 1, source, pageText, chunkInfo, link, doc.getText()));
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