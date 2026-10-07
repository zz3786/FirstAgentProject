package org.example.toolregistry.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.toolregistry.ToolRegistry;
import org.example.toolregistry.model.ToolDescriptor;
import org.example.toolregistry.model.ToolSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现的工具注册中心（D67）
 *
 * <h3>为什么用 ConcurrentHashMap</h3>
 * <p>
 * 读多写少，且写入（D68 动态刷新）是偶发事件。
 * {@code ConcurrentHashMap} 保证读写并发安全，且 get/put 都是 O(1)。
 *
 * <h3>线程安全说明</h3>
 * <ul>
 *   <li>{@code register} / {@code unregister}：单 key 原子操作，天然线程安全</li>
 *   <li>{@code clear} + {@code registerAll}：D68 刷新场景，
 *       两者之间短暂窗口内注册表为空——调用方应持有刷新锁，
 *       或直接调 {@code registerAll}（不 clear）靠覆盖实现</li>
 *   <li>{@code listAll} / {@code getCallbacks}：读快照，
 *       迭代过程中写入不影响返回结果</li>
 * </ul>
 *
 * <h3>为什么不做持久化</h3>
 * <p>
 * 工具是"运行时能力"——MCP Server 重启后工具可能变化，
 * 持久化旧数据反而会导致"幽灵工具"。工具注册表应始终反映"当前可用的能力"。
 * 需要审计历史变更的话，在 {@code register} / {@code unregister} 里打日志即可。
 */
@Slf4j
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    /**
     * 主索引：工具名 → 描述符。
     * <p>以工具名为唯一 key，同名注册会覆盖旧值。
     */
    private final Map<String, ToolDescriptor> byName = new ConcurrentHashMap<>();

    // ==================== 注册 ====================

    @Override
    public void register(ToolDescriptor descriptor) {
        ToolDescriptor old = byName.put(descriptor.name(), descriptor);
        if (old == null) {
            log.info("[ToolRegistry] 注册工具: name={}, source={}, category={}",
                    descriptor.name(), descriptor.source(), descriptor.category());
        } else {
            log.warn("[ToolRegistry] 覆盖同名工具: name={}, 旧 source={}, 新 source={}",
                    descriptor.name(), old.source(), descriptor.source());
        }
    }

    @Override
    public void registerAll(List<ToolDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }
        for (ToolDescriptor d : descriptors) {
            register(d);
        }
        log.info("[ToolRegistry] 批量注册完成，当前总数={}", byName.size());
    }

    @Override
    public boolean unregister(String name) {
        ToolDescriptor removed = byName.remove(name);
        if (removed != null) {
            log.info("[ToolRegistry] 注销工具: name={}, source={}",
                    name, removed.source());
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        int size = byName.size();
        byName.clear();
        log.warn("[ToolRegistry] 清空所有工具，原有 {} 个", size);
    }

    // ==================== 查询 ====================

    @Override
    public Optional<ToolDescriptor> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<ToolDescriptor> listAll() {
        return List.copyOf(byName.values());
    }

    @Override
    public List<ToolDescriptor> listBySource(ToolSource source) {
        return byName.values().stream()
                .filter(d -> d.source() == source)
                .toList();
    }

    @Override
    public List<ToolDescriptor> listBySourceId(String sourceId) {
        if (sourceId == null) {
            return List.of();
        }
        return byName.values().stream()
                .filter(d -> sourceId.equals(d.sourceId()))
                .toList();
    }

    @Override
    public List<ToolDescriptor> listByCategory(String category) {
        if (category == null) {
            return List.of();
        }
        return byName.values().stream()
                .filter(d -> category.equals(d.category()))
                .toList();
    }

    @Override
    public Set<String> listNames() {
        return Set.copyOf(byName.keySet());
    }

    // ==================== 消费 ====================

    @Override
    public ToolCallback[] getCallbacks() {
        return byName.values().stream()
                .map(ToolDescriptor::callback)
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getCallbacks(Set<String> allowedNames) {
        if (allowedNames == null || allowedNames.isEmpty()) {
            // 语义决策：空集合 = 不加白名单限制，返回全部
            // 若希望"空集合 = 什么都没"，调用方应显式传 Set.of("__none__")
            return getCallbacks();
        }
        return byName.entrySet().stream()
                .filter(e -> allowedNames.contains(e.getKey()))
                .map(e -> e.getValue().callback())
                .toArray(ToolCallback[]::new);
    }
}
