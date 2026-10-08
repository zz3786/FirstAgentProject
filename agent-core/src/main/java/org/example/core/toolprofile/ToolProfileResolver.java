package org.example.core.toolprofile;

import lombok.extern.slf4j.Slf4j;
import org.example.core.toolprofile.config.ToolProfileProperties;
import org.example.core.toolprofile.model.ToolProfile;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 工具画像解析器
 *
 * <h3>职责</h3>
 * <p>
 * 根据业务名 / profile 名，从 {@link ToolRegistry} 过滤出工具数组。
 * <p>
 * 把"工具注册中心的全量工具"转化为"某个业务场景可见的工具"——
 * 这一层抽象让"工具画像"从代码里硬编码提升为 yml 声明。
 *
 * <h3>核心 API</h3>
 * <ul>
 *   <li>{@link #resolveForConsumer(String)}——按业务名解析（推荐）</li>
 *   <li>{@link #resolve(String)}——按 profile 名解析</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>业务名未配置 profile → WARN + 返回空数组</li>
 *   <li>profile 名不存在 → WARN + 返回空数组</li>
 *   <li>解析过程异常 → WARN + 返回空数组</li>
 * </ul>
 * <p>
 * 空数组的选择理由：宁可"业务不能用工具"（有日志可查），
 * 也好过"业务获得全部工具"（越权风险）。
 *
 * <h3>与 ToolRegistry 的关系</h3>
 * <p>
 * 本类是 Registry 的<b>视图层</b>——Registry 管"有哪些"，
 * 本类管"给谁看哪些"。Registry 不感知 Profile，本类只读 Registry。
 */
@Slf4j
@Component
public class ToolProfileResolver {

    private final ToolRegistry toolRegistry;
    private final ToolProfileProperties properties;

    public ToolProfileResolver(ToolRegistry toolRegistry,
                               ToolProfileProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        log.info("[ToolProfile] 初始化：profiles={}, consumers={}",
                properties.getProfiles().keySet(),
                properties.getConsumers());
    }

    // ==================== 主入口 ====================

    /**
     * 按业务名解析——推荐入口。
     * <p>
     * 业务方代码只认一个"业务名"（如 "chat-service"），
     * 具体用哪个 profile 由 yml 决定。
     *
     * @param consumerName 业务名
     * @return 该业务的工具数组；配置缺失或异常时返回空数组
     */
    public ToolCallback[] resolveForConsumer(String consumerName) {
        String profileName = properties.getConsumers().get(consumerName);
        if (profileName == null || profileName.isBlank()) {
            log.warn("[ToolProfile] 业务 [{}] 未配置 profile——返回空数组。" +
                            "请在 app-tool-profiles.yml 的 consumers 段补充配置",
                    consumerName);
            return new ToolCallback[0];
        }
        return resolve(profileName);
    }

    /**
     * 按 profile 名解析。
     *
     * @param profileName profile 名
     * @return 过滤后的工具数组
     */
    public ToolCallback[] resolve(String profileName) {
        ToolProfile profile = properties.getProfiles().get(profileName);
        if (profile == null) {
            log.warn("[ToolProfile] profile [{}] 不存在——返回空数组", profileName);
            return new ToolCallback[0];
        }

        try {
            Set<String> names = resolveNames(profile);
            ToolCallback[] result = toolRegistry.getCallbacks(names);
            log.debug("[ToolProfile] 解析 profile=[{}]: {} 个工具 → {}",
                    profileName, names.size(),
                    names.stream().limit(10).toList());
            return result;
        } catch (Exception e) {
            log.error("[ToolProfile] 解析 profile [{}] 失败——返回空数组", profileName, e);
            return new ToolCallback[0];
        }
    }

    // ==================== 核心过滤逻辑 ====================

    /**
     * 根据 Profile 定义，从 Registry 全量工具里筛出目标工具名集合。
     *
     * <h3>过滤顺序</h3>
     * <ol>
     *   <li>从全量工具名开始</li>
     *   <li>若有 include 条件 → 取 include 的并集，与全量取交集</li>
     *   <li>减去 exclude-tools</li>
     *   <li>（D69）按 min-security-level 过滤</li>
     * </ol>
     */
    private Set<String> resolveNames(ToolProfile profile) {
        Set<String> all = toolRegistry.listNames();

        // ── ① 无 include 条件 → 从全量开始
        if (!profile.hasIncludeConditions()) {
            Set<String> result = new HashSet<>(all);
            result.removeAll(profile.getExcludeTools());
            return result;
        }

        // ── ② 有 include 条件 → 计算并集
        Set<String> includes = new HashSet<>();

        // 2a. 按分类
        for (String category : profile.getIncludeCategories()) {
            toolRegistry.listByCategory(category)
                    .stream()
                    .map(ToolDescriptor::name)
                    .forEach(includes::add);
        }

        // 2b. 按工具名
        includes.addAll(profile.getIncludeTools());

        // 2c. 按来源
        for (ToolSource source : profile.getIncludeSources()) {
            toolRegistry.listBySource(source)
                    .stream()
                    .map(ToolDescriptor::name)
                    .forEach(includes::add);
        }

        // 2d. 与全量取交集（防止 include 里写了不存在的工具名）
        includes.retainAll(all);

        // ── ③ 减去 exclude-tools
        includes.removeAll(profile.getExcludeTools());

        // ── ④ D69 预留：min-security-level 过滤
        //    本轮仅记录日志，不实际过滤——D69 会启用
        if (profile.getMinSecurityLevel() != null) {
            log.debug("[ToolProfile] profile 声明了 min-security-level={}（D69 启用）",
                    profile.getMinSecurityLevel());
        }

        return includes;
    }

    // ==================== 便捷方法 ====================

    /**
     * 按业务名取工具名集合——PlannerService / PlanValidator 等需要工具名的场景用。
     */
    public Set<String> resolveNamesForConsumer(String consumerName) {
        String profileName = properties.getConsumers().get(consumerName);
        if (profileName == null) {
            return Set.of();
        }
        ToolProfile profile = properties.getProfiles().get(profileName);
        if (profile == null) {
            return Set.of();
        }
        return resolveNames(profile);
    }
}