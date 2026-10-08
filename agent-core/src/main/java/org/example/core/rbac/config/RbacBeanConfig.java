package org.example.core.rbac.config;

import lombok.extern.slf4j.Slf4j;
import org.example.core.rbac.ToolAuthorizer;
import org.example.core.rbac.impl.RbacToolAuthorizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RBAC 装配（D69）
 */
@Slf4j
@Configuration
public class RbacBeanConfig {

    @Bean
    public ToolAuthorizer toolAuthorizer(RbacProperties properties) {
        log.info("[D69] 装配 ToolAuthorizer（RbacToolAuthorizer）");
        return new RbacToolAuthorizer(properties);
    }
}