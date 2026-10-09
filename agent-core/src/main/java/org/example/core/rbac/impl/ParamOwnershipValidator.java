package org.example.core.rbac.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorization;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 参数归属校验器（D69 第二轮）
 *
 * <h3>订单归属规则（D69 更新——纯数字版）</h3>
 * <p>
 * 用订单号的<b>长度 + 首位数字</b>判断归属：
 * <ul>
 *   <li>长度 4 位（如 1001）→ 公共订单，任何租户可查</li>
 *   <li>长度 5 位、首位 1（如 10001）→ hospital-a</li>
 *   <li>长度 5 位、首位 2（如 20001）→ hospital-b</li>
 *   <li>其他 → 公共（兜底放行）</li>
 * </ul>
 *
 * <h3>为什么不用前缀编码</h3>
 * <p>
 * 前缀（如 "A-1001"）会被 LLM 在参数传递时"规范化"掉——
 * 要么加、要么删，不可控。用纯数字的"长度+首位"编码，
 * LLM 无论怎么处理都改变不了语义。
 *
 * <h3>容错</h3>
 * <ul>
 *   <li>入参 JSON 解析失败 → 放行（保守）</li>
 *   <li>ToolContext 无 userId → 放行</li>
 *   <li>orderId 为空 → 放行（留给工具校验）</li>
 * </ul>
 */
@Slf4j
@Component
public class ParamOwnershipValidator {

    private final ObjectMapper objectMapper;

    public ParamOwnershipValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolAuthorization validate(String toolName, String toolInput, ToolContext toolContext) {
        return switch (toolName) {
            case "getOrderStatus", "mcp_getOrderStatus" ->
                    validateOrderOwnership(toolInput, toolContext);
            default -> ToolAuthorization.allow();
        };
    }

    // ==================== 订单归属校验 ====================

    private ToolAuthorization validateOrderOwnership(String toolInput, ToolContext toolContext) {
        String orderId = parseField(toolInput, "orderId");
        if (orderId == null || orderId.isBlank()) {
            return ToolAuthorization.allow();
        }

        String tenantId = extractTenantId(toolContext);
        if (tenantId == null) {
            return ToolAuthorization.allow();
        }

        String requiredTenant = inferTenantFromOrderId(orderId);

        if (requiredTenant == null) {
            log.debug("[D69-Param] 公共订单: orderId={}, tenant={}", orderId, tenantId);
            return ToolAuthorization.allow();
        }

        if (requiredTenant.equals(tenantId)) {
            log.debug("[D69-Param] 订单归属校验通过: orderId={}, tenant={}",
                    orderId, tenantId);
            return ToolAuthorization.allow();
        }

        String reason = String.format(
                "订单 [%s] 属于租户 [%s]，当前用户租户 [%s]",
                orderId, requiredTenant, tenantId);
        log.warn("[D69-Param] 跨租户访问被拒: {}", reason);
        return ToolAuthorization.deny(reason);
    }

    /**
     * 从订单号推断归属租户。
     * <ul>
     *   <li>4 位数字 → null（公共）</li>
     *   <li>5 位、首位 1 → hospital-a</li>
     *   <li>5 位、首位 2 → hospital-b</li>
     *   <li>其他 → null（公共，兜底）</li>
     * </ul>
     */
    private String inferTenantFromOrderId(String orderId) {
        if (orderId == null || !orderId.matches("\\d+")) {
            return null;
        }
        if (orderId.length() == 5) {
            if (orderId.startsWith("1")) {
                return "hospital-a";
            }
            if (orderId.startsWith("2")) {
                return "hospital-b";
            }
        }
        if (orderId.length() == 6) {
            if (orderId.startsWith("A")) {
                return "hospital-a";
            }
            if (orderId.startsWith("B")) {
                return "hospital-b";
            }
        }
        return null;
    }

    // ==================== 辅助 ====================

    private String parseField(String toolInput, String fieldName) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                return null;
            }
            return value.asText();
        } catch (Exception e) {
            log.debug("[D69-Param] 解析 toolInput 失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractTenantId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get("userId");
        if (v == null) {
            return null;
        }
        String fullUserId = v.toString();
        int idx = fullUserId.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        return fullUserId.substring(0, idx);
    }
}