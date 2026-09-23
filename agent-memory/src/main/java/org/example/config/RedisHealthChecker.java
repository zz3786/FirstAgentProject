package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Slf4j
@Component
public class RedisHealthChecker {


    private final StringRedisTemplate redis;

    @Value("${spring.data.redis.host:unknown}")
    private String host;

    @Value("${spring.data.redis.port:0}")
    private int port;

    @Value("${spring.data.redis.database:0}")
    private int database;

    public RedisHealthChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void check() {
        log.info("========== Redis 连接检查 ==========");
        log.info("目标地址: {}:{}, database={}", host, port, database);

        long start = System.currentTimeMillis();
        try {
            // 1. ping 测试
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            log.info("✅ PING 响应: {}", pong);

            // 2. 简单读写测试
            String testKey = "health:check:" + System.currentTimeMillis();
            redis.opsForValue().set(testKey, "ok");
            String value = redis.opsForValue().get(testKey);
            redis.delete(testKey);

            if ("ok".equals(value)) {
                log.info("✅ 读写测试通过（耗时 {} ms）", System.currentTimeMillis() - start);
            } else {
                log.error("❌ 读写测试失败，写入的值读回来不对: {}", value);
            }

            // 3. 打印服务端信息
            Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) connection ->
                    connection.serverCommands().info());
            if (info != null) {
                log.info("✅ Redis 版本: {}", info.getProperty("redis_version"));
                log.info("✅ 已用内存: {}", info.getProperty("used_memory_human"));
                log.info("✅ 当前连接数: {}", info.getProperty("connected_clients"));
            }

            log.info("========== Redis 连接正常 ==========");

        } catch (Exception e) {
            log.error("========== Redis 连接失败 ==========");
            log.error("❌ 地址: {}:{}", host, port);
            log.error("❌ 原因: {}", e.getMessage());
            log.error("❌ 详细堆栈:", e);
            log.error("========== 排查建议 ==========");
            log.error("1. Redis 服务是否启动？试试 docker ps 或 systemctl status redis");
            log.error("2. 网络是否可达？本地试 telnet {} {}", host, port);
            log.error("3. 密码是否正确？application.yml 中的 password 配置");
            log.error("4. 防火墙/安全组是否放行 {} 端口？", port);
            log.error("5. 如果只想本地开发，建议改用 localhost + 本地 Redis");
        }
    }
}