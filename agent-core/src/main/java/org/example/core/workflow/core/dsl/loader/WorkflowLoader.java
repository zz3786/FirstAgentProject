package org.example.core.workflow.core.dsl.loader;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.core.dsl.config.DslProperties;
import org.example.core.workflow.core.dsl.model.WorkflowDefinition;
import org.example.core.workflow.core.dsl.parser.WorkflowParser;
import org.example.core.workflow.core.dsl.parser.WorkflowValidator;
import org.example.core.workflow.core.dsl.registry.WorkflowDefinitionRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作流加载器
 * <p>
 * 双源加载：
 * <ol>
 *   <li>classpath（主路径）——{@code classpath*:workflows/*.yaml}——打包/IDEA 运行都生效</li>
 *   <li>文件系统（可选）——{@code ./workflows/}——生产环境覆盖 classpath 定义</li>
 * </ol>
 */
@Slf4j
@Component
public class WorkflowLoader {

    private final DslProperties dslProps;
    private final WorkflowParser parser;
    private final WorkflowValidator validator;
    private final WorkflowDefinitionRegistry registry;

    public WorkflowLoader(DslProperties dslProps, WorkflowParser parser,
                          WorkflowValidator validator,
                          WorkflowDefinitionRegistry registry) {
        this.dslProps = dslProps;
        this.parser = parser;
        this.validator = validator;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAllOnStartup() {

        if (!dslProps.isEnabled()) {
            log.info("[DSL] 工作流加载已关闭（app.dsl.enabled=false）");
            return;
        }

        log.info("========== 开始加载工作流定义 ==========");
        log.info("JVM 工作目录: {}", System.getProperty("user.dir"));

        int fromClasspath = loadFromClasspath();
        int fromFs = loadFromFileSystem();

        log.info("========== 工作流加载完成: classpath={}, fs={}, 共 {} ==========",
                fromClasspath, fromFs, fromClasspath + fromFs);
    }

    /**
     * 从 classpath 扫描——主路径
     */
    private int loadFromClasspath() {
        int loaded = 0;
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(getClass().getClassLoader());

        for (String pattern : dslProps.getClasspathPatterns()) {
            try {
                Resource[] resources = resolver.getResources(pattern);
                log.info("[classpath] pattern={} 匹配 {} 个", pattern, resources.length);

                for (Resource res : resources) {
                    try {
                        String content = new String(
                                res.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8);
                        WorkflowDefinition def = parser.parse(content, "classpath:" + res.getFilename());
                        validator.validate(def);
                        registry.register(def);
                        loaded++;
                        log.info("  ✓ 加载成功: {}", res.getFilename());
                    } catch (Exception e) {
                        log.error("  ✗ 加载失败: {} - {}", res.getFilename(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("[classpath] 扫描失败: pattern={}", pattern, e);
            }
        }
        return loaded;
    }

    /**
     * 从文件系统扫描——可选覆盖
     */
    private int loadFromFileSystem() {
        int loaded = 0;
        for (String dirStr : dslProps.getScanDirs()) {
            Path dir = Path.of(dirStr);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir, 1)) {
                List<Path> wfFiles = files
                        .filter(Files::isRegularFile)
                        .filter(this::isWorkflowFile)
                        .toList();

                for (Path file : wfFiles) {
                    try {
                        WorkflowDefinition def = parser.parseFile(file);
                        validator.validate(def);
                        registry.register(def);
                        loaded++;
                        log.info("  ✓ [fs] 加载成功: {}", file);
                    } catch (Exception e) {
                        log.error("  ✗ [fs] 加载失败: {} - {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("[fs] 扫描失败: {}", dir, e);
            }
        }
        return loaded;
    }

    public WorkflowDefinition loadFromString(String content, String source) {
        WorkflowDefinition def = parser.parse(content, source);
        validator.validate(def);
        registry.register(def);
        return def;
    }

    public int reload() {
        log.info("手动触发热重载...");
        return loadFromClasspath() + loadFromFileSystem();
    }

    private boolean isWorkflowFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }
}