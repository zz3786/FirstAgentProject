package org.example.cache;

import org.example.cache.service.SemanticCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SemanticCacheQuickTest {

    @Autowired
    private SemanticCacheService cacheService;

    @Test
    void testCacheCore() {
        System.out.println("\n========== 语义缓存核心测试 ==========");

        String tenantA = "user-alice";
        String tenantB = "user-B";

        // 清空旧缓存
        cacheService.clearByUser(tenantA);

        // ① 存
        cacheService.store("家庭医生签约注意事项", "答案A", tenantA);

        // ② 精确命中
        String hit1 = cacheService.lookup("家庭医生签约注意事项", tenantA);
        System.out.println("【精确命中】" + hit1);
        assertNotNull(hit1, "精确匹配应该命中");

        // ③ 相似命中
        String hit2 = cacheService.lookup("签约家庭医生需要注意啥", tenantA);
        System.out.println("【相似命中】" + hit2);
        assertNotNull(hit2, "语义相似应该命中");

        // ④ 不相关——不命中
        String miss = cacheService.lookup("今天天气怎么样", tenantA);
        System.out.println("【不相关查询】" + miss);
        assertNull(miss, "不相关问题不应命中");

        // ⑤ 跨租户——不命中
        String crossTenant = cacheService.lookup("家庭医生签约注意事项", tenantB);
        System.out.println("【跨租户查询】" + crossTenant);
        assertNull(crossTenant, "跨租户不应命中");

        System.out.println("========== 全部通过 ✅ ==========\n");
    }
}