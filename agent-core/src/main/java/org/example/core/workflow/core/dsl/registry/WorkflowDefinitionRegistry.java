package org.example.core.workflow.core.dsl.registry;

import lombok.extern.slf4j.Slf4j;
import org.example.core.workflow.core.dsl.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流定义注册表——保存所有"已加载"的工作流
 * <p>
 * <b>线程安全</b>：用 ConcurrentHashMap——支持运行时热更新。
 */
@Slf4j
@Component
public class WorkflowDefinitionRegistry {

    private final Map<String, WorkflowDefinition> registry = new ConcurrentHashMap<>();

    /**
     * 注册（或覆盖）一个工作流
     */
    public void register(WorkflowDefinition def) {
        WorkflowDefinition old = registry.put(def.getId(), def);
        if (old == null) {
            log.info("工作流已注册: id={}, version={}", def.getId(), def.getVersion());
        } else {
            log.info("工作流已更新: id={}, 旧版本={} → 新版本={}",
                    def.getId(), old.getVersion(), def.getVersion());
        }
    }

    /**
     * 按 id 获取
     */
    public WorkflowDefinition get(String id) {
        return registry.get(id);
    }

    /**
     * 列出所有工作流
     */
    public Collection<WorkflowDefinition> listAll() {
        return registry.values();
    }

    /**
     * 是否已注册
     */
    public boolean contains(String id) {
        return registry.containsKey(id);
    }

    /**
     * 清除——测试或热更新时用
     */
    public void clear() {
        registry.clear();
    }

    /**
     * 移除
     */
    public void unregister(String id) {
        WorkflowDefinition removed = registry.remove(id);
        if (removed != null) {
            log.info("工作流已移除: id={}", id);
        }
    }
}