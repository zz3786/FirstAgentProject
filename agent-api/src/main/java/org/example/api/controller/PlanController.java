package org.example.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.plan.PlanAndExecuteService;
import org.example.core.plan.model.PlanRequest;
import org.example.core.plan.model.PlanResult;
import org.example.api.utils.SessionUtils;
import org.example.api.utils.TenantRequestUtils;
import org.springframework.web.bind.annotation.*;

/**
 * Plan-and-Execute 入口
 */
@Slf4j
@RestController
@RequestMapping("/agent/plan")
@Tag(name = "计划和执行", description = "把需求分解成每步计划然后选择工具执行（规划--执行--汇总）") // ★
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
    @Operation(summary = "计划执入口", description = "输入问题，调用LLM解析，并行处理并汇总结果")
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
    @Schema(description = "用户输入信息体") // ★ 可以用 Schema 描述 DTO
    public record Body(
            @Schema(description = "用户输入需求", example = "帮我计算123+345是多少，456*789=？") String userInput,
            @Schema(description = "会话ID", example = "")String sessionId
    ) {}
}