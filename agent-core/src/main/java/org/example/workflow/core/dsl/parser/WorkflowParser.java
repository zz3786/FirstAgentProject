package org.example.workflow.core.dsl.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.core.dsl.exception.DslParseException;
import org.example.workflow.core.dsl.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 工作流解析器——YAML/JSON → WorkflowDefinition
 * <p>
 * <b>YAML vs JSON 支持</b>：
 * YAML 更易读、支持注释——推荐用于人工编辑。
 * JSON 更严格、便于机器生成——两种都支持。
 */
@Slf4j
@Component
public class WorkflowParser {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 从文件解析
     */
    public WorkflowDefinition parseFile(Path path) {
        try {
            String content = Files.readString(path);
            return parse(content, path.toString());
        } catch (Exception e) {
            throw new DslParseException("解析文件失败: " + path, e);
        }
    }

    /**
     * 从 classpath 资源解析
     */
    public WorkflowDefinition parseResource(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new DslParseException("资源不存在: " + resourcePath);
            }
            String content = new String(is.readAllBytes());
            return parse(content, resourcePath);
        } catch (DslParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DslParseException("读取资源失败: " + resourcePath, e);
        }
    }

    /**
     * 从字符串解析——自动识别 YAML/JSON
     */
    @SuppressWarnings("unchecked")
    public WorkflowDefinition parse(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new DslParseException("内容为空: " + source);
        }

        try {
            // 顶层结构是 { workflow: { ... } }
            Map<String, Object> root;
            if (content.trim().startsWith("{")) {
                root = jsonMapper.readValue(content, Map.class);
            } else {
                root = yamlMapper.readValue(content, Map.class);
            }

            Object workflowNode = root.get("workflow");
            if (workflowNode == null) {
                throw new DslParseException("缺少 workflow 顶层节点: " + source);
            }

            WorkflowDefinition def = yamlMapper.convertValue(workflowNode, WorkflowDefinition.class);
            log.info("解析成功: id={}, version={}, nodes={}",
                    def.getId(), def.getVersion(),
                    def.getNodes() == null ? 0 : def.getNodes().size());
            return def;

        } catch (DslParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DslParseException("解析失败: " + source + " - " + e.getMessage(), e);
        }
    }
}