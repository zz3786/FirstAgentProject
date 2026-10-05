package org.example.workflow.conditional.model;

/**
 * 订单状态枚举
 * <p>
 * 集中管理所有可能状态——避免字符串散落各处。
 */
public enum OrderStatus {
    PENDING_PAYMENT("待付款"),
    SHIPPED("已发货"),
    SIGNED("已签收"),
    REFUNDING("退款中"),
    REFUNDED("已退款"),
    UNKNOWN("未知状态");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 安全解析——未知输入返回 UNKNOWN，不抛异常 */
    public static OrderStatus fromCode(String code) {
        if (code == null || code.isBlank()) return UNKNOWN;
        for (OrderStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return UNKNOWN;
    }
}