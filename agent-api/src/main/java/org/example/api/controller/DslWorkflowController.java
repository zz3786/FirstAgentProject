package org.example.api.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.dsl.engine.DynamicWorkflowEngine;
import org.example.core.workflow.core.dsl.loader.WorkflowLoader;
import org.example.core.workflow.core.dsl.model.WorkflowDefinition;
import org.example.core.workflow.core.dsl.registry.WorkflowDefinitionRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
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

    @Resource
    private DynamicWorkflowEngine engine;

    /**
     * 执行工作流
     * <p>
     * 请求体：
     * <pre>
     * {
     *   "orderId": "1001",
     *   "fullUserId": "hospital-a:user-alice",
     *   "executionId": "dsl-test-001"
     * }
     * </pre>
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<Map<String, Object>> execute(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        // ① 校验工作流存在
        WorkflowDefinition def = registry.get(id);
        if (def == null) {
            return ApiResponse.fail(404, "工作流不存在: " + id);
        }

        // ② 构造 State
        String orderId = body.getOrDefault("orderId", "1001");
        String fullUserId = body.getOrDefault("fullUserId", "hospital-a:user-alice");
        String executionId = body.getOrDefault("executionId","dsl-" + System.currentTimeMillis());

        OrderWorkflowState state = new OrderWorkflowState(orderId, fullUserId, executionId, 60_000L);

        // ③ 执行
        engine.execute(id, state);

        // ④ 返回结果
        return ApiResponse.ok(Map.of(
                "executionId", executionId,
                "status", state.getStatus(),
                "branchTaken", state.getBranchTaken() == null ? "" : state.getBranchTaken(),
                "finalMessage", state.getFinalMessage() == null ? "" : state.getFinalMessage(),
                "traces", state.getTraces()
        ));
    }

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