package org.example.core.workflow.conditional.order.fetcher;

/**
 * 数据源拉取接口
 * <p>
 * 每个 fetcher 负责从一类数据源拉数据——用于并行执行。
 *
 * @param <T> 返回类型
 */
public interface DataFetcher<T> {
    /** 数据源名——用于并行任务名 */
    String sourceName();

    /** 拉取数据 */
    T fetch(String orderId);
}