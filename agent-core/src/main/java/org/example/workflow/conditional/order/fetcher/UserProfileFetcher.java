package org.example.workflow.conditional.order.fetcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户画像——从用户中心拉取
 */
@Slf4j
@Component
public class UserProfileFetcher implements DataFetcher<String> {

    @Override
    public String sourceName() {
        return "user-profile";
    }

    @Override
    public String fetch(String orderId) {
        sleep(150);
        return "VIP 钻石会员，累计消费 12,345 元，偏好电子产品";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}