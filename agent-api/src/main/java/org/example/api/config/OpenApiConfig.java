package org.example.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger API 全局配置
 *
 * @author YourName
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FirstAgentProject API 文档")
                        .version("v3.0.0")
                        .description("中小型企业生产级 AI Agent 平台接口文档。包含：多智能体协作、RAG 知识库、工作流引擎、MCP 工具等核心能力。")
                        .contact(new Contact().name("研发部").email("dev@example.com")));
    }
}