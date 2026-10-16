package org.example.observability.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import io.micrometer.context.ContextRegistry;

@Slf4j
@Configuration
public class ReactorMdcConfig {

    @PostConstruct
    public void init() {
        // 1. 注册 MDC 为 ThreadLocalAccessor
        //    key="mdc" 是内部标识，传入的是 MDC 的 get/set/remove 方法引用
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                "mdc",
                MDC::getCopyOfContextMap,   // 捕获：从当前线程 MDC 拿 Map
                MDC::setContextMap,         // 恢复：把 Map 塞回新线程 MDC
                MDC::clear                  // 清理：清空新线程 MDC
        );

        // 2. 开启 Reactor 自动上下文传播
        //    必须在所有 Flux/Mono 创建之前调用——应用启动时最合适
        Hooks.enableAutomaticContextPropagation();

        log.info("[ReactorMdcConfig] MDC ThreadLocalAccessor 已注册，自动传播已启用");
    }
}