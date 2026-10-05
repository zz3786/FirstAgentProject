package org.example.core.workflow.conditional.order.nodes;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.conditional.order.model.OrderStatus;
import org.example.core.workflow.conditional.order.model.OrderInfo;
import org.example.core.workflow.conditional.order.fetcher.LogisticsFetcher;
import org.example.core.workflow.conditional.order.fetcher.OrderFetcher;
import org.example.core.workflow.conditional.order.fetcher.UserProfileFetcher;
import org.example.core.workflow.conditional.order.model.OrderBasicInfo;
import org.example.core.workflow.conditional.order.model.OrderEnrichContext;
import org.example.core.workflow.conditional.order.model.OrderWorkflowState;
import org.example.core.workflow.core.config.WorkflowEngineProperties;
import org.example.core.workflow.core.node.AbstractWorkflowNode;
import org.example.core.workflow.core.parallel.ParallelExecutor;
import org.example.core.workflow.core.parallel.model.ParallelResult;
import org.example.core.workflow.core.parallel.model.ParallelTask;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 并行拉取节点——D60 核心演示
 * <p>
 * 从三个数据源同时拉数据：
 * <ul>
 *   <li>订单基础信息——关键任务</li>
 *   <li>物流信息——非关键，有兜底</li>
 *   <li>用户画像——非关键，有兜底</li>
 * </ul>
 * <p>
 * 串行耗时 ≈ 200 + 300 + 150 = 650ms
 * 并行耗时 ≈ max(200, 300, 150) = 300ms
 */
@Slf4j
@Component
public class ParallelFetchNode extends AbstractWorkflowNode {

    private final ParallelExecutor parallelExecutor;
    private final OrderFetcher orderFetcher;
    private final LogisticsFetcher logisticsFetcher;
    private final UserProfileFetcher userProfileFetcher;

    public ParallelFetchNode(WorkflowEngineProperties props,
                             ParallelExecutor parallelExecutor,
                             OrderFetcher orderFetcher,
                             LogisticsFetcher logisticsFetcher,
                             UserProfileFetcher userProfileFetcher) {
        super(props);
        this.parallelExecutor = parallelExecutor;
        this.orderFetcher = orderFetcher;
        this.logisticsFetcher = logisticsFetcher;
        this.userProfileFetcher = userProfileFetcher;
    }

    @Override
    public String name() {
        return "ParallelFetchNode";
    }

    @Override
    protected String doExecute(OrderWorkflowState state) {
        String orderId = state.getOrderId();

        // ① 定义三个并行任务
        List<ParallelTask<Object>> tasks = List.of(
                // 订单基础——关键任务
                ParallelTask.critical("order-basic",
                        () -> orderFetcher.fetch(orderId)),

                // 物流——非关键，兜底为"暂无物流信息"
                ParallelTask.withFallback("logistics",
                        () -> logisticsFetcher.fetch(orderId),
                        "暂无物流信息"),

                // 用户画像——非关键，兜底为"普通用户"
                ParallelTask.withFallback("user-profile",
                        () -> userProfileFetcher.fetch(orderId),
                        "普通用户")
        );

        // ② 并行执行
        ParallelResult<Object> result = parallelExecutor.executeAll(tasks);

        // ③ 关键任务失败 → 抛异常
        if (!result.allCriticalSucceeded()) {
            String err = result.errors().getOrDefault("order-basic", "关键任务失败");
            throw new IllegalStateException("并行拉取失败: " + err);
        }

        // ④ 组装聚合上下文
        OrderEnrichContext ctx = new OrderEnrichContext();
        ctx.setOrderId(orderId);
        ctx.setOrderBasic((OrderBasicInfo) result.get("order-basic"));
        ctx.setLogistics((String) result.getOrDefault("logistics", "暂无物流信息"));
        ctx.setUserProfile((String) result.getOrDefault("user-profile", "普通用户"));
        ctx.setParallelCostMs(result.totalCostMs());
        ctx.setDegraded(!result.isSuccess("logistics") || !result.isSuccess("user-profile"));

        // ⑤ 塞进 state（通过 orderInfo 传递）
        // 此处简化——实际项目里应该在 OrderWorkflowState 里加字段
        state.setOrderInfo(new OrderInfo(
                ctx.getOrderBasic().orderId(),
                parseStatus(ctx.getOrderBasic().status()),
                ctx.getOrderBasic().buyerName(),
                "13800000000",
                ctx.getOrderBasic().amount()
        ));

        String summary = String.format(
                "并行拉取完成: 订单=%s, 物流=%s, 画像=%s, 耗时=%dms%s",
                ctx.getOrderBasic().orderId(),
                ctx.getLogistics(),
                ctx.getUserProfile(),
                ctx.getParallelCostMs(),
                ctx.isDegraded() ? "（有降级）" : "");

        return summary;
    }

    private OrderStatus parseStatus(String code) {
        try {
            return OrderStatus.valueOf(code);
        } catch (Exception e) {
            return OrderStatus.UNKNOWN;
        }
    }

    /** 拉取失败——整体失败 */
    @Override
    protected boolean isDegradable() {
        return false;
    }
}