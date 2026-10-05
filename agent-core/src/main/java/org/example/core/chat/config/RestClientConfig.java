package org.example.core.chat.config;

import org.example.core.chat.interceptor.HttpLoggingInterceptor;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer loggingCustomizer(HttpLoggingInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}