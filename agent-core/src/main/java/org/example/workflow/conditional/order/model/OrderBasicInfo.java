package org.example.workflow.conditional.order.model;

/**
 * 订单基础信息
 */
public record OrderBasicInfo(
        String orderId,
        String status,
        String buyerName,
        double amount
) {}