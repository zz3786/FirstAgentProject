package org.example.core.workflow.conditional.order.fetcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 物流信息——演示 @Retryable 注解式重试
 * <p>
 * <b>执行流程</b>：
 * <pre>
 *   fetch() 被调用
 *      ↓
 *   AOP 拦截 → 执行
 *      ↓
 *   抛 RuntimeException?
 *      ├─ 否 → 返回结果
 *      └─ 是 → 检查是否重试
 *               ├─ 重试次数 < 3 → 等 backoff 后重试
 *               └─ 重试次数 = 3 → 调用 @Recover 方法
 * </pre>
 * <p>
 * <b>注意事项</b>：
 * <ul>
 *   <li>必须加 @EnableRetry 到配置类</li>
 *   <li>同类内部方法调用不生效（AOP 代理限制）</li>
 *   <li>异常类型必须匹配 retryFor</li>
 *   <li>@Recover 方法的签名必须是"异常 + 原方法参数"，返回值类型与原方法一致</li>
 * </ul>
 */
@Slf4j
@Component
public class LogisticsFetcher implements DataFetcher<String> {

    private static final java.util.Random RND = new java.util.Random();

    @Override
    public String sourceName() {
        return "logistics";
    }

    @Override
    @Retryable(
            retryFor = {RuntimeException.class},
            noRetryFor = {IllegalArgumentException.class},
            maxAttempts = 3,
            backoff = @Backoff(
                    delay = 200,
                    multiplier = 2.0,
                    maxDelay = 2000,
                    random = true
            )
    )
    public String fetch(String orderId) {
        sleep(300);
        if (RND.nextInt(10) < 3) {
            log.warn("[LogisticsFetcher] 模拟故障");
            throw new RuntimeException("物流系统暂时不可用");
        }
        return "快递单号 SF" + (System.currentTimeMillis() % 1000000)
                + "，预计明天下午送达";
    }

    /**
     * 兜底方法——重试 3 次都失败后调用
     * <p>
     * <b>签名规则</b>：
     * <ul>
     *   <li>返回值类型必须与原方法一致</li>
     *   <li>第一个参数是异常</li>
     *   <li>后续参数与原方法参数一致</li>
     * </ul>
     */
    @Recover
    public String recoverFetch(RuntimeException e, String orderId) {
        log.warn("[LogisticsFetcher] 三次重试均失败，降级处理 order={}", orderId, e);
        return "暂无物流信息（系统繁忙）";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}