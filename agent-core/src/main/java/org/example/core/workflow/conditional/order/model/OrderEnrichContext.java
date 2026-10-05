package org.example.core.workflow.conditional.order.model;

import lombok.Data;

/**
 * 订单增强上下文——并行拉取多个数据源后的聚合结果
 */
@Data
public class OrderEnrichContext {

    private String orderId;

    /** 订单基础信息——关键任务，必须成功 */
    private OrderBasicInfo orderBasic;

    /** 物流信息——非关键，可能为空 */
    private String logistics;

    /** 用户画像——非关键，可能为空 */
    private String userProfile;

    /** 并行执行的总耗时 */
    private long parallelCostMs;

    /** 是否有降级 */
    private boolean degraded;
}