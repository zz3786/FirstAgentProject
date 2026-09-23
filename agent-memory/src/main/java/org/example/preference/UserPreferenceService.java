package org.example.preference;

import org.example.utils.SensitiveDataMasker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 处理用户偏爱身份画像等记忆
 */
@Service
public class UserPreferenceService {

    private static final String PREFIX = "USER_PREF:";
    private final StringRedisTemplate redis;

    public UserPreferenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 保存单个偏好，如 city=合肥 */
    public void save(String userId, String key, String value) {
        value = SensitiveDataMasker.mask(value);   // ← 存之前脱敏
        redis.opsForHash().put(PREFIX + userId, key, value);
        redis.expire(PREFIX + userId, Duration.ofDays(360));
    }

    /** 批量保存 */
    public void saveAll(String userId, Map<String, String> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return;
        }
        redis.opsForHash().putAll(PREFIX + userId, prefs);
    }

    /** 读取全部偏好 */
    public Map<String, String> getAll(String userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(PREFIX + userId);
        Map<String, String> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /** 读取单个偏好 */
    public String get(String userId, String key) {
        Object v = redis.opsForHash().get(PREFIX + userId, key);
        return v == null ? null : v.toString();
    }

    /** 渲染成 System Prompt 片段 */
    public String renderAsSystemText(String userId) {
        Map<String, String> prefs = getAll(userId);
        if (prefs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已知用户偏好：\n");
        prefs.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}