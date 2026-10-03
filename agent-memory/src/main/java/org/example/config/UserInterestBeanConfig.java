package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.example.advisor.UserInterestAdvisor;
import org.example.interest.UserInterestService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D53 兴趣标签 Advisor 装配
 */
@Slf4j
@Configuration
public class UserInterestBeanConfig {

    @Bean
    public UserInterestAdvisor userInterestAdvisor(UserInterestService interestService) {
        log.info("装配 UserInterestAdvisor（order=120）");
        return new UserInterestAdvisor(interestService);
    }
}