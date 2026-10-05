package org.example.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.plan.PlanAndExecuteService;
import org.example.core.plan.model.PlanRequest;
import org.example.core.plan.model.PlanResult;
import org.example.utils.SessionUtils;
import org.example.utils.TenantRequestUtils;
import org.springframework.web.bind.annotation.*;

/**
 * Plan-and-Execute 入口
 */
@Slf4j
@RestController
@RequestMapping("/agent/plan")
public class PlanController {

    @Resource
    private PlanAndExecuteService planService;

    /**
     * 触发一次 Plan-and-Execute
     * <p>
     * 请求体：
     * <pre>
     * { "userInput": "帮我算 125+456，把结果和它的一半都存到待办里" }
     * </pre>
     */
    @PostMapping("/execute")
    public ApiResponse<PlanResult> execute(@RequestBody Body body,
                                           HttpServletRequest request) {
        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);
        String fullUserId = tenantId + ":" + rawUserId;
        String conversationId = fullUserId + ":" + (body.sessionId() == null
                ? "plan-default" : body.sessionId());

        PlanRequest req = new PlanRequest(body.userInput(), conversationId, fullUserId);
        return ApiResponse.ok(planService.execute(req));
    }

    /** 恢复中断的执行 */
    @PostMapping("/resume/{executionId}")
    public ApiResponse<PlanResult> resume(@PathVariable String executionId) {
        return ApiResponse.ok(planService.resume(executionId));
    }

    /** 请求体 */
    public record Body(String userInput, String sessionId) {}
}