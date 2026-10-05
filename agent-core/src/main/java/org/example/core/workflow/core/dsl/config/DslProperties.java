package org.example.core.workflow.core.dsl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DSL 工作流配置
 * <p>
 * yml 前缀：app.dsl.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.dsl")
public class DslProperties {

    /** 总开关——关掉则不加载任何工作流 */
    private boolean enabled = true;

    /** 是否在加载时做静态校验——生产必须开 */
    private boolean validateOnLoad = true;

    /** 是否启用文件系统热更新（WatchService 监听）——生产建议关 */
    private boolean hotReloadEnabled = false;

    /**
     * 文件系统扫描目录列表——相对 JVM 工作目录
     * <p>
     * classpath 加载不受此影响，这个只控制"额外从磁盘读哪些目录"。
     */
    private String[] scanDirs = {"./workflows", "./config/workflows"};

    /**
     * 单次工作流执行的最大跳转次数——防止 switch 死循环
     */
    private int maxJumps = 100;

    /**
     * 表达式求值失败时的默认值
     * <p>
     * true = 视为通过（宽松）；false = 视为不通过（严格）
     * <p>
     * 生产建议 false——表达式写错要立刻暴露，不要静默通过。
     */
    private boolean expressionFailOpen = false;

    /**
     * classpath 扫描的路径模式
     * <p>
     * 默认扫 classpath 下所有 workflows 目录的 yaml/yml/json。
     */
    private String[] classpathPatterns = {
            "classpath*:workflows/*.yaml",
            "classpath*:workflows/*.yml",
            "classpath*:workflows/*.json"
    };
}