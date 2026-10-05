package org.example.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.workflow.conditional.OrderConditionalWorkflow;
import org.example.workflow.core.hitl.model.HitlTask;
import org.example.workflow.core.hitl.service.HitlService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * HITL 人工操作入口
 */
@Slf4j
@RestController
@RequestMapping("/hitl")
public class HitlController {

    @Resource
    private HitlService hitlService;

    @Resource
    private OrderConditionalWorkflow orderWorkflow;

    /**
     * 列出待处理任务
     */
    @GetMapping("/pending")
    public ApiResponse<List<HitlTask>> listPending() {
        return ApiResponse.ok(hitlService.listPending());
    }

    /**
     * 查看任务详情
     */
    @GetMapping("/task/{taskId}")
    public ApiResponse<HitlTask> getTask(@PathVariable String taskId) {
        return ApiResponse.ok(hitlService.get(taskId));
    }

    /**
     * 批准 + 自动恢复工作流
     */
    @PostMapping("/task/{taskId}/approve")
    public ApiResponse<Map<String, Object>> approve(
            @PathVariable String taskId,
            @RequestBody(required = false) DecisionBody body) {

        String operator = body == null ? "admin" : body.operatorId();
        String comment = body == null ? "" : body.comment();

        // ① 记录决策
        hitlService.approve(taskId, operator, operator, comment);

        // ② 触发工作流恢复
        Map<String, Object> result = orderWorkflow.resume(taskId);
        return ApiResponse.ok(result);
    }

    /**
     * 拒绝
     */
    @PostMapping("/task/{taskId}/reject")
    public ApiResponse<Map<String, Object>> reject(
            @PathVariable String taskId,
            @RequestBody(required = false) DecisionBody body) {

        String operator = body == null ? "admin" : body.operatorId();
        String comment = body == null ? "" : body.comment();

        hitlService.reject(taskId, operator, operator, comment);
        return ApiResponse.ok(orderWorkflow.resume(taskId));
    }

    /**
     * 修改后继续
     */
    @PostMapping("/task/{taskId}/modify")
    public ApiResponse<Map<String, Object>> modify(
            @PathVariable String taskId,
            @RequestBody ModifyBody body) {

        hitlService.modify(taskId, body.operatorId(), body.operatorId(), body.comment(), body.modifications());
        return ApiResponse.ok(orderWorkflow.resume(taskId));
    }

    // ==================== 请求体 ====================

    public record DecisionBody(String operatorId, String comment) {}

    public record ModifyBody(
            String operatorId,
            String comment,
            Map<String, Object> modifications
    ) {}
}