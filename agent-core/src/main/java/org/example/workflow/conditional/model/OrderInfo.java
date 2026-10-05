package org.example.workflow.conditional.model;

/**
 * 订单数据模型——从 OrderTools 查询返回
 */
public record OrderInfo(
        String orderId,
        OrderStatus status,
        String buyerName,
        String buyerContact,   // 手机号/邮箱
        double amount
) {}