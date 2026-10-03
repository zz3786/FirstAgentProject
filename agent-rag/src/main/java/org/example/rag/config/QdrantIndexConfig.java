package org.example.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;   // ★ 正确 import
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class QdrantIndexConfig {

    /**
     * 为 Qdrant 的过滤字段建立 Payload Index
     * <p>
     * <b>为什么必须建索引</b>：
     * 没有索引时，Qdrant 的过滤是"遍历所有点逐个判断"——O(n) 全量扫描。
     * 数据量大时性能断崖式下降。建索引后，过滤用倒排索引定位候选集，
     * 复杂度降到 O(log n)，30 秒的查询可以变成 30 毫秒。
     * <p>
     * <b>幂等性</b>：索引已存在时会抛异常，捕获后忽略即可。
     */
    @Bean
    public ApplicationRunner qdrantPayloadIndexInitializer(QdrantClient qdrantClient) {
        return args -> {
            String collection = "agent-rag";

            // keyword 索引：字符串精确匹配（department、content_type）
            createIndexIfAbsent(qdrantClient, collection,
                    "department", PayloadSchemaType.Keyword);

            createIndexIfAbsent(qdrantClient, collection,
                    "content_type", PayloadSchemaType.Keyword);

            // integer 索引：数值范围查询（year）
            createIndexIfAbsent(qdrantClient, collection,
                    "year", PayloadSchemaType.Integer);

            // ★ D46 新增：密级和状态
            createIndexIfAbsent(qdrantClient, collection,
                    "security_level", PayloadSchemaType.Integer);

            createIndexIfAbsent(qdrantClient, collection,
                    "status", PayloadSchemaType.Keyword);

            createIndexIfAbsent(qdrantClient, collection,
                    "tenant_id", PayloadSchemaType.Keyword);   // ★ 新增
        };
    }

    /**
     * 创建索引，已存在则跳过
     * <p>
     * Qdrant 的 createPayloadIndexAsync 在索引已存在时抛异常，
     * 这是正常的幂等冲突——记录后忽略，不影响启动。
     */
    private void createIndexIfAbsent(QdrantClient client, String collection,
                                     String field, PayloadSchemaType type) {
        try {
            client.createPayloadIndexAsync(
                    collection,
                    field,
                    type,
                    null,    // indexParams：默认参数即可
                    true,    // wait：同步等待索引建完
                    null,    // ordering
                    null     // timeout
            ).get();
            log.info("Qdrant Payload Index 创建成功: {}.{} = {}", collection, field, type);
        } catch (Exception e) {
            // 索引已存在时抛异常——不算错误
            log.info("Qdrant Payload Index 可能已存在（跳过）: {}.{}", collection, field);
        }
    }
}
