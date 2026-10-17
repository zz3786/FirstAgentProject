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
     *
     * <h3>修复说明</h3>
     * 以前只要不是纯数字就直接 return null（放行）。
     * 导致传入 "B10001"（hospital-b的订单）时，被当作公共订单，发生越权。
     * 现在严格校验格式：
     * 1. 如果是 5 位纯数字：首位 1 -> hospital-a，首位 2 -> hospital-b。
     * 2. 如果是 1位字母+5位数字：A -> hospital-a，B -> hospital-b。
     * 3. 其他（如 4 位数字） -> 视为公共订单放行。
     */
    private String inferTenantFromOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }

        // ① 匹配纯数字（5位）
        if (orderId.matches("^\\d{5}$")) {
            if (orderId.startsWith("1")) return "hospital-a";
            if (orderId.startsWith("2")) return "hospital-b";
        }

        // ② 匹配字母前缀（如 A10001, B10001）
        if (orderId.matches("^[a-zA-Z]\\d{5}$")) {
            String prefix = orderId.substring(0, 1).toUpperCase();
            if ("A".equals(prefix)) return "hospital-a";
            if ("B".equals(prefix)) return "hospital-b";
        }

        // 其他未识别格式（如 1001、9999），保守当作公共订单放行
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