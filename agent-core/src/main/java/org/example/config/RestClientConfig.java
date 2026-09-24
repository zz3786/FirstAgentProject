package org.example.rag.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer loggingCustomizer(org.example.rag.config.HttpLoggingInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}