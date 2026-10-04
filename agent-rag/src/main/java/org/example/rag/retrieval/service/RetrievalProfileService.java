package org.example.rag.retrieval.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 检索画像服务
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>从 Redis 读/写用户检索画像</li>
 *   <li>把存储格式（Hash 字符串）转成 RetrievalProfile 对象</li>
 * </ol>
 * <p>
 * <b>不负责</b>：从行为推断画像——那是"隐式画像任务"（阶段 2）的职责。
 *
 * <h3>存储结构</h3>
 * <pre>
 * RETRIEVAL_PROFILE:{userId} (hash)
 *   ├── preferred_departments : "财务部,人事部"
 *   ├── preferred_doc_types   : "制度,通知"
 *   ├── weight_bias_vec       : "+0.05"
 *   ├── weight_bias_kw        : "-0.05"
 *   ├── top_k_bias            : "+1"
 *   ├── updated_at            : "1790060400000"
 *   └── source                : "explicit"
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>用纯 userId 作为 key</b>——和 tenantId 一致，跨 session 共享画像</li>
 *   <li><b>TTL 90 天</b>——防止用户换工作/换项目后画像永久错乱</li>
 *   <li><b>读写失败降级为空画像</b>——画像丢失不该阻断检索</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalProfileService {

    private static final String PREFIX = "RETRIEVAL_PROFILE:";

    /** 画像 TTL——90 天不更新自动失效 */
    private static final Duration TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;

    /** 总开关——默认关闭，灰度上线 */
    @Value("${app.retrieval.profile.enabled:false}")
    private boolean enabled;

    public RetrievalProfileService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 读取 ====================

    /**
     * 读取用户检索画像
     * <p>
     * <b>降级策略</b>：任何异常 → 返回空画像（不用画像，但检索正常走）。
     *
     * @param userId 用户 ID（纯 userId，不含 sessionTag）
     * @return 画像；无画像/读取失败时返回 empty
     */
    public RetrievalProfile get(String userId) {
        if (!enabled) {
            return RetrievalProfile.empty();
        }
        if (userId == null || userId.isBlank()) {
            return RetrievalProfile.empty();
        }

        try {
            Map<Object, Object> raw = redis.opsForHash().entries(PREFIX + userId);
            if (raw == null || raw.isEmpty()) {
                return RetrievalProfile.empty();
            }

            RetrievalProfile profile = new RetrievalProfile(
                    splitCsv(getString(raw, "preferred_departments")),
                    splitCsv(getString(raw, "preferred_doc_types")),
                    parseDouble(getString(raw, "weight_bias_vec"), 0.0),
                    parseDouble(getString(raw, "weight_bias_kw"), 0.0),
                    parseInt(getString(raw, "top_k_bias"), 0)
            );

            log.info("检索画像加载: userId={}, profile={}", userId, profile);
            return profile;

        } catch (Exception e) {
            log.warn("读取检索画像失败，降级为空: userId={}", userId, e);
            return RetrievalProfile.empty();
        }
    }

    // ==================== 写入 ====================

    /**
     * 保存用户检索画像
     * <p>
     * 由两个来源调用：
     * <ol>
     *   <li>显式工具——用户说"以后优先给我看财务部"</li>
     *   <li>隐式任务（阶段 2）——定时统计 CONV_MEM 生成</li>
     * </ol>
     */
    public void save(String userId, RetrievalProfile profile, String source) {
        if (!enabled) {
            log.debug("检索画像功能未启用，跳过保存");
            return;
        }
        if (userId == null || userId.isBlank() || profile == null) {
            return;
        }

        try {
            String key = PREFIX + userId;
            redis.opsForHash().putAll(key, Map.of(
                    "preferred_departments", String.join(",", profile.preferredDepartments()),
                    "preferred_doc_types", String.join(",", profile.preferredDocTypes()),
                    "weight_bias_vec", String.valueOf(profile.weightBiasVec()),
                    "weight_bias_kw", String.valueOf(profile.weightBiasKw()),
                    "top_k_bias", String.valueOf(profile.topKBias()),
                    "updated_at", String.valueOf(System.currentTimeMillis()),
                    "source", source == null ? "unknown" : source
            ));
            redis.expire(key, TTL);
            log.info("检索画像已保存: userId={}, source={}, profile={}", userId, source, profile);

        } catch (Exception e) {
            log.warn("保存检索画像失败: userId={}", userId, e);
        }
    }

    // ==================== 辅助 ====================

    private String getString(Map<Object, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    private double parseDouble(String s, double defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseInt(String s, int defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}