package org.example.mcpserver.resource;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP 资源：RAG 文档原文读取（D65 / D78 配置化）
 *
 * <h3>D78 改动</h3>
 * <p>
 * 原实现用 private static final String FILE_STORAGE_DIR = "D:/...";——Windows 硬编码。
 * 改为 @Value 注入——优先取 app.rag.ingest.file-storage-dir，
 * 与 agent-api 侧 IngestProperties 使用同一配置名（跨部署单元语义对齐）。
 * 默认值 ./data/rag-files/ 是容器内工作目录下的相对路径——Docker 部署挂载卷到此即可。
 *
 * <h3>与 RagFileController 的关系</h3>
 * <p>
 * agent-api 的 RagFileController 提供 HTTP 下载（浏览器用）；
 * 本类提供 MCP Resource 接口（MCP Client 用）。
 * 两者底层读同一目录——但配置文件独立（MCP Server 是独立部署单元）。
 * 生产部署时务必通过环境变量 RAG_FILE_STORAGE_DIR 让两端指向同一挂载卷。
 */
@Slf4j
@Component
public class RagDocumentMcpResource {

    private final String fileStorageDir;

    public RagDocumentMcpResource(
            @Value("${app.rag.ingest.file-storage-dir:./data/rag-files/}")
            String fileStorageDir) {
        this.fileStorageDir = fileStorageDir;

        // ★ D78：启动时立即校验——早失败胜于运行时 500
        Path dir = Path.of(fileStorageDir);
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            log.info("[MCP-Resource] 文档存储目录就绪: {}", dir.toAbsolutePath());
        } else {
            log.warn("[MCP-Resource] 文档存储目录不存在或不是目录: {} —— " +
                            "resources/read 会返回'目录不存在'，请检查配置或挂载",
                    dir.toAbsolutePath());
        }
    }

    @McpResource(
            uri = "rag://doc/{docId}",
            name = "RAG 文档原文",
            description = "根据文档 ID 读取知识库中文档的完整原文",
            mimeType = "text/plain"
    )
    public String readDocument(String docId) {
        log.info("[MCP-Resource] 读取文档: docId={}", docId);

        if (docId == null || docId.isBlank()
                || docId.contains("..")
                || docId.contains("/")
                || docId.contains("\\")) {
            return "非法的文档 ID";
        }

        Path dir = Path.of(fileStorageDir);
        if (!Files.exists(dir)) {
            log.warn("[MCP-Resource] 存储目录不存在: {}", fileStorageDir);
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