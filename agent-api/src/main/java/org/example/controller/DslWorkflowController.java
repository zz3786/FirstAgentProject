package org.example.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.workflow.core.dsl.loader.WorkflowLoader;
import org.example.workflow.core.dsl.model.WorkflowDefinition;
import org.example.workflow.core.dsl.registry.WorkflowDefinitionRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DSL 工作流管理接口
 * <p>
 * 提供：查询、热重载、上传新工作流
 */
@Slf4j
@RestController
@RequestMapping("/dsl/workflow")
public class DslWorkflowController {

    @Resource
    private WorkflowDefinitionRegistry registry;

    @Resource
    private WorkflowLoader loader;

    /** 列出所有已加载的工作流 */
    @GetMapping("/list")
    public ApiResponse<Collection<WorkflowDefinition>> list() {
        return ApiResponse.ok(registry.listAll());
    }

    /** 按 id 查详情 */
    @GetMapping("/{id}")
    public ApiResponse<WorkflowDefinition> get(@PathVariable String id) {
        WorkflowDefinition def = registry.get(id);
        if (def == null) {
            return ApiResponse.fail(404, "工作流不存在: " + id);
        }
        return ApiResponse.ok(def);
    }

    /** 手动热重载 */
    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reload() {
        int count = loader.reload();
        return ApiResponse.ok(Map.of(
                "reloaded", count,
                "message", "热重载完成"
        ));
    }

    /** 上传新工作流 */
    @PostMapping("/upload")
    public ApiResponse<WorkflowDefinition> upload(@RequestBody String content) {
        WorkflowDefinition def = loader.loadFromString(content, "api-upload");
        return ApiResponse.ok(def);
    }
}