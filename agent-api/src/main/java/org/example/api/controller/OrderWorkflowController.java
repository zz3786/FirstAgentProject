package org.example.api.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.api.utils.SessionUtils;
import org.example.api.utils.TenantRequestUtils;
import org.example.core.workflow.conditional.order.OrderConditionalWorkflow;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 订单条件工作流入口
 */
@Slf4j
@RestController
@RequestMapping("/workflow/order")
public class OrderWorkflowController {

    @Resource
    private OrderConditionalWorkflow workflow;

    /**
     * 处理订单
     * <p>
     * 示例：POST /fap/workflow/order/process/1001
     */
    @PostMapping("/process/{orderId}")
    public ApiResponse<Map<String, Object>> process(
            @PathVariable String orderId,
            HttpServletRequest request) {

        String tenantId = TenantRequestUtils.getTenantId(request);
        String rawUserId = SessionUtils.getUserId(request);
        String fullUserId = tenantId + ":" + rawUserId;

        log.info("收到订单处理请求: orderId={}, user={}", orderId, fullUserId);

        Map<String, Object> result = workflow.process(orderId, fullUserId);
        return ApiResponse.ok(result);
    }
}