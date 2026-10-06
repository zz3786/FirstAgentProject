package org.example.mcpserver.resource;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP 资源：RAG 文档原文读取（D65）
 *
 * <h3>Resource 和 Tool 的本质区别</h3>
 * <pre>
 * Tools     —— 模型驱动。模型决定“我要调 getOrderStatus”。
 * Resources —— 应用/用户驱动。前端决定“我要看 docId 的原文”。
 * </pre>
 * <p>
 * RAG 文档天然是 Resource，不是 Tool。模型不应该“决定”去读某篇文档——
 * 它应该通过 RAG 检索拿到相关片段。但用户/前端可以“主动”请求
 * 查看某篇文档的完整原文，这就是 Resource 的用武之地。
 *
 * <h3>URI 模板</h3>
 * <p>
 * {@code rag://doc/{docId}} 是一个 URI 模板。
 * Client 端发起读取时，把 {@code {docId}} 替换为实际 ID：
 * <pre>
 *   rag://doc/00a4e0e7-817e-4305-a909-34c001e39155
 * </pre>
 *
 * <h3>和 RagFileController 的关系</h3>
 * <p>
 * {@code agent-api} 里的 {@code RagFileController.downloadByDocId()}
 * 提供的是 HTTP 下载接口（给浏览器用）。
 * 本类提供的是 MCP Resource 接口（给 MCP Client 用）。
 * 两者底层读的是同一个文件目录，但协议不同、消费者不同。
 */
@Slf4j
@Component
public class RagDocumentMcpResource {

    /**
     * 文件存储目录——与 agent-api 的 IngestProperties.fileStorageDir 保持一致。
     * 生产环境中这个值应从配置中心读取，而非硬编码。
     */
    private static final String FILE_STORAGE_DIR = "D:/DevelopmentTool/qdrant/rag-files/";

    /**
     * MCP 资源：按 docId 读取文档原文
     *
     * <p>Client 发起请求：
     * <pre>
     *   resources/read
     *   { "uri": "rag://doc/{docId}" }
     * </pre>
     *
     * <p>Server 返回文本内容（mimeType = text/plain）。
     *
     * @param docId 文档 ID（UUID 格式）
     * @return 文档原文；找不到时返回提示文本
     */
    @McpResource(
            uri = "rag://doc/{docId}",
            name = "RAG 文档原文",
            description = "根据文档 ID 读取知识库中文档的完整原文",
            mimeType = "text/plain"
    )
    public String readDocument(String docId) {

        log.info("[MCP-Resource] 读取文档: docId={}", docId);

        // ① 安全校验——防止路径穿越
        if (docId == null || docId.isBlank()
                || docId.contains("..")
                || docId.contains("/")
                || docId.contains("\\")) {
            return "非法的文档 ID";
        }

        // ② 查找文件（匹配 {docId}.*）
        Path dir = Path.of(FILE_STORAGE_DIR);
        if (!Files.exists(dir)) {
            log.warn("[MCP-Resource] 存储目录不存在: {}", FILE_STORAGE_DIR);
            return "文档存储目录不存在";
        }

        try (var stream = Files.list(dir)) {
            Path target = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(docId + "."))
                    .findFirst()
                    .orElse(null);

            if (target == null) {
                log.warn("[MCP-Resource] 文档不存在: docId={}", docId);
                return "未找到文档: " + docId;
            }

            // ③ 读取内容——限制最大长度防止超大文件撑爆上下文
            String content = Files.readString(target);
            int maxLen = 50_000;
            if (content.length() > maxLen) {
                content = content.substring(0, maxLen) + "\n\n...(文档过长，已截断)";
            }
            return content;

        } catch (IOException e) {
            log.error("[MCP-Resource] 读取文档失败: docId={}", docId, e);
            return "读取文档失败: " + e.getMessage();
        }
    }
}